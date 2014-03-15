package com.phantom.ds.conversation

import scala.concurrent.{ Future, ExecutionContext, future }
import com.phantom.model._
import com.phantom.dataAccess.DatabaseSupport
import java.io.{ ByteArrayInputStream, File, FileOutputStream }
import com.phantom.ds.{ BasicCrypto, DSConfiguration }
import com.phantom.model.BlockUserByConversationResponse
import com.phantom.model.ConversationUpdateResponse
import com.phantom.model.Conversation
import com.phantom.model.ConversationItem
import com.phantom.model.ConversationInsertResponse
import com.phantom.ds.framework.{ Dates, Logging }
import akka.actor.ActorRef
import com.phantom.ds.integration.twilio.SendInviteToStubUsers
import com.phantom.ds.integration.apple.AppleNotification
import com.phantom.ds.framework.exception.PhantomException
import scala.slick.session.Session
import java.util.UUID
import org.apache.commons.codec.binary.Base64
import java.security.MessageDigest
import org.joda.time.DateTime
import com.phantom.ds.framework.crypto._
import com.phantom.ds.framework.protocol.defaults._

import org.jets3t.service.impl.rest.httpclient.RestS3Service
import org.jets3t.service.security.AWSCredentials
import org.jets3t.service.model.S3Object
import org.jets3t.service.acl.{ AccessControlList, GroupGrantee, Permission }

/**
 * Created by Neosavvy
 *
 * User: adamparrish
 * Date: 12/7/13
 * Time: 2:01 PM
 */
trait ConversationService {

  def findFeed(userId : Long, paging : Paging) : Future[List[FeedEntry]]

  def startConversation(fromUserId : Long,
                        contactNumbers : Set[String],
                        imageText : String,
                        imageUrl : String) : Future[ConversationInsertResponse]

  def respondToConversation(userId : Long,
                            conversationId : Long,
                            imageText : String,
                            imageUrl : String) : Future[ConversationUpdateResponse]

  def saveFileForConversationId(image : Array[Byte], conversationId : Long) : String

  def blockByConversationId(userId : Long, conversationId : Long) : Future[BlockUserByConversationResponse]

  def viewConversationItem(conversationItemId : Long, userId : Long) : Future[Boolean]

  def deleteConversation(userId : Long, conversationId : Long) : Future[Int]

  def deleteConversationItem(userId : Long, conversationItemId : Long) : Future[Int]

  def findPhotoUrlById(photoId : Long) : Future[Option[String]]
}

object ConversationService extends DSConfiguration with BasicCrypto {

  def apply(twilioActor : ActorRef, appleActor : ActorRef)(implicit ec : ExecutionContext) = new ConversationService with DatabaseSupport with Logging {

    //TODO: this is going to grow..let's also move this into its own object
    private def sanitizeConversation(conversation : Conversation, loggedInUser : PhantomUser, itemsLength : Int) : FEConversation = {

      val isLoggedInUserFromUser = (conversation.fromUser == loggedInUser.id.get)
      if (isLoggedInUserFromUser) {
        FEConversation(
          conversation.id.get,
          encryptField(conversation.receiverPhoneNumber),
          conversation.lastUpdated,
          itemsLength
        )
      } else {
        FEConversation(
          conversation.id.get,
          "",
          conversation.lastUpdated,
          itemsLength
        )
      }
    }

    private def sanitizeConversationItems(items : List[ConversationItem], loggedInUser : PhantomUser) : List[FEConversationItem] = {

      items.map { conversationItem =>
        val isFromUser = loggedInUser.id.get == conversationItem.fromUser

        log.debug(s"sanitizeConversationItem $conversationItem.createdDate")

        FEConversationItem(
          conversationItem.id.get,
          conversationItem.conversationId,
          encryptField(conversationItem.imageUrl),
          encryptField(conversationItem.imageText),
          conversationItem.isViewed,
          conversationItem.createdDate,
          isFromUser
        )
      }

    }

    def sanitizeFeed(feed : List[FeedEntry], loggedInUser : PhantomUser) : Future[List[FeedWrapper]] = {
      future {
        log.debug(s"sanitizeFeed: $feed")
        feed.map { feedEntry =>
          val conversation = sanitizeConversation(feedEntry.conversation, loggedInUser, feedEntry.items.length)
          val conversationItems = sanitizeConversationItems(feedEntry.items, loggedInUser)
          FeedWrapper(conversation, conversationItems)
        }
      }
    }

    def findFeed(userId : Long, paging : Paging) : Future[List[FeedEntry]] = {
      future {
        val rawFeed = db.withSession { implicit session : Session =>
          conversationDao.findConversationsAndItemsOperation(userId)
        }
        FeedFolder.foldFeed(userId, rawFeed, paging)
      }
    }

    //TODO: ther'es a lot in this..we should make a new service just for starting conversations
    def startConversation(fromUserId : Long,
                          contactNumbers : Set[String],
                          imageText : String,
                          imageUrl : String) : Future[ConversationInsertResponse] = {
      for {
        (nonUsers, allUsers) <- partitionUsers(contactNumbers)
        (stubUsers, users) <- partitionStubUsers(allUsers)
        (newStubUsers, response) <- createStubUsersAndRoots(nonUsers, users ++ stubUsers, fromUserId, imageText, imageUrl)
        _ <- sendInvitations(stubUsers ++ newStubUsers)
        tokens <- getTokens(allUsers.map(_.id.get))
        _ <- sendNewConversationNotifications(allUsers, tokens)
      } yield response
    }

    private def getTokens(userIds : Seq[Long]) : Future[Map[Long, Set[String]]] = {
      future {
        sessions.findTokensByUserId(userIds)
      }
    }

    private def partitionUsers(contactNumbers : Set[String]) : Future[(Set[String], Seq[PhantomUser])] = {
      partition(phantomUsersDao.findByPhoneNumbers(contactNumbers), contactNumbers)
    }

    private def partitionStubUsers(users : Seq[PhantomUser]) : Future[(Seq[PhantomUser], Seq[PhantomUser])] = {
      Future.successful(users.partition(_.status == Stub))
    }

    private def partition(phantomsF : Future[List[PhantomUser]], contactNumbers : Set[String]) : Future[(Set[String], Seq[PhantomUser])] = {
      phantomsF.map { users =>
        val existingNumbers = users.map(_.phoneNumber).flatten.toSet
        val nonUsers = contactNumbers.diff(existingNumbers)
        (nonUsers, users)
      }
    }

    private def createStubUsersAndRoots(numbers : Set[String], users : Seq[PhantomUser], fromUserId : Long, imageText : String, imageUrl : String) : Future[(Seq[PhantomUser], ConversationInsertResponse)] = {
      future {
        db.withTransaction { implicit session : Session =>
          val newUsers = phantomUsersDao.insertAllOperation(numbers.map(x => PhantomUser(None, UUID.randomUUID, None, None, None, false, Some(x), Stub, 0)).toSeq)
          val conversations = (users ++ newUsers).map(x => Conversation(None, x.id.getOrElse(-1), fromUserId, x.phoneNumber.get))
          val createdConversations = conversationDao.insertAllOperation(conversations)
          conversationItemDao.insertAllOperation(createConversationItemRoots(createdConversations, fromUserId, imageText, imageUrl))
          (newUsers, ConversationInsertResponse(createdConversations.size))
        }
      }
    }

    private def createConversationItemRoots(conversations : Seq[Conversation], fromUserId : Long, imageText : String, imageUrl : String) : Seq[ConversationItem] = {
      conversations.map(x => ConversationItem(None, x.id.getOrElse(-1), imageUrl, imageText, x.toUser, fromUserId))
    }

    private def sendNewConversationNotifications(users : Seq[PhantomUser], tokens : Map[Long, Set[String]]) : Future[Unit] = {
      tokens.foreach { token =>
        log.debug(s">>>>>>> sending a push notification for a new conversation $users and $token")
      }

      future {
        users.foreach { user =>
          sendConversationNotifications(user, tokens.getOrElse(user.id.get, Set.empty))
        }
      }
    }

    private def sendConversationNotifications(user : PhantomUser, tokens : Set[String]) : Future[Unit] = {
      future {
        log.debug(s"notifications are $tokens")
        if (user.settingNewPicture) {
          tokens.foreach {
            token =>
              val note = AppleNotification(user.settingSound, Some(token))
              log.debug(s"sending an apple notification to the apple actor $note for user ${user.email}")
              appleActor ! note
          }
        }
      }
    }

    //TODO: FIRE A MESSAGE PER USER, NOT PER BATCH
    private def sendInvitations(stubUsers : Seq[PhantomUser]) : Future[Unit] = {
      //intentionally not creating a future here..as sending msgs in non blocking
      Future.successful {
        val invitable = stubUsers.filter(_.invitationCount < UserConfiguration.invitationMax)
        if (!invitable.isEmpty) {
          twilioActor ! SendInviteToStubUsers(invitable)
        }
      }
    }

    def findToUser(loggedInUser : Long, conversation : Conversation) : Long = {
      if (conversation.toUser == loggedInUser) {
        conversation.fromUser
      } else {
        conversation.toUser
      }
    }

    override def respondToConversation(loggedInUser : Long, conversationId : Long, imageText : String, imageUrl : String) : Future[ConversationUpdateResponse] = {
      future {
        db.withTransaction { implicit session =>
          val citem = for {
            conversation <- conversationDao.findByIdAndUserOperation(conversationId, loggedInUser)
          } yield conversationItemDao.insertOperation(
            ConversationItem(
              None,
              conversationId,
              imageUrl,
              imageText,
              findToUser(loggedInUser, conversation),
              loggedInUser
            )
          )

          citem.map { x =>
            val conversationF = conversationDao.findById(conversationId)
            conversationF onSuccess {
              case conversation =>

                // fire off APNS notifications
                val userFuture = future(phantomUsersDao.find(x.toUser))
                val tokensFuture = getTokens(Seq(x.toUser))
                for {
                  user : Option[PhantomUser] <- userFuture
                  tokens <- tokensFuture
                  _ <- future {
                    user.map { u : PhantomUser =>
                      log.debug(s"sending an apple push notification for a response from a previous conversation item $u.id")
                      sendConversationNotifications(u, tokens.getOrElse(u.id.get, Set.empty))
                    }
                  }
                } yield (user, tokens)

                conversationDao.updateById(Conversation(
                  conversation.id,
                  conversation.toUser,
                  conversation.fromUser,
                  conversation.receiverPhoneNumber))
            }
            ConversationUpdateResponse(1)
          }.getOrElse(throw PhantomException.nonExistentConversation)
        }
      }
    }

    def saveFileForConversationId(image : Array[Byte], conversationId : Long) : String = {
      val randomImageName : String = MessageDigest.getInstance("MD5").digest(DateTime.now().toString().getBytes).map("%02X".format(_)).mkString
      val imageUrl = conversationId + "/" + randomImageName

      val awsAccessKey = AWS.accessKeyId
      val awsSecretKey = AWS.secretKey
      val awsCredentials = new AWSCredentials(awsAccessKey, awsSecretKey)
      val s3Service = new RestS3Service(awsCredentials)
      val bucketName = AWS.bucket
      val bucket = s3Service.getBucket(bucketName)
      val fileObject = s3Service.putObject(bucket, {
        val acl = s3Service.getBucketAcl(bucket)
        acl.grantPermission(GroupGrantee.ALL_USERS, Permission.PERMISSION_READ)

        val tempObj = new S3Object(imageUrl)
        tempObj.setDataInputStream(new ByteArrayInputStream(image))
        tempObj.setAcl(acl)
        tempObj.setContentType("image/jpg")
        tempObj
      })

      s3Service.createUnsignedObjectUrl(bucketName,
        fileObject.getKey,
        false, false, false)
    }

    def blockByConversationId(userId : Long, conversationId : Long) : Future[BlockUserByConversationResponse] = {
      future {
        db.withTransaction { implicit session =>

          val updatedOpt = for {
            conversation <- conversationDao.findByIdAndUserOperation(conversationId, userId)
            updateCount <- Option(contacts.blockContactOperation(userId, getOtherUserId(conversation, userId)))
          } yield (updateCount, conversation)

          updatedOpt match {
            case None => throw PhantomException.nonExistentConversation
            case Some((0, conversation)) =>
              backfillBlockedContact(userId, getOtherUserId(conversation, userId)); BlockUserByConversationResponse(conversation.id.get, true)
            case Some((_, conversation)) => BlockUserByConversationResponse(conversation.id.get, true)
          }
        }
      }
    }

    def viewConversationItem(conversationItemId : Long, userId : Long) : Future[Boolean] = {
      future {
        db.withTransaction { implicit session =>
          conversationItemDao.updateViewedOperation(conversationItemId, userId) > 0
        }
      }
    }

    def deleteConversation(userId : Long, conversationId : Long) : Future[Int] = {
      future {
        db.withTransaction { implicit session =>
          val items = conversationItemDao.findByConversationIdAndUserOperation(conversationId, userId)
          val (fromItems, toItems) = items.partition(_.fromUser == userId)
          conversationItemDao.updateDeletedByFromUserOperation(fromItems.map(_.id.get) : _*)
          conversationItemDao.updateDeletedByToUserOperation(toItems.map(_.id.get) : _*)

        }
      }
    }

    def deleteConversationItem(userId : Long, conversationItemId : Long) : Future[Int] = {
      future {
        db.withTransaction { implicit session =>
          val itemOpt = conversationItemDao.findByIdAndUserOperation(conversationItemId, userId)
          itemOpt.map { x =>
            if (x.fromUser == userId) {
              conversationItemDao.updateDeletedByFromUserOperation(conversationItemId)
            } else {
              conversationItemDao.updateDeletedByToUserOperation(conversationItemId)
            }
          }.getOrElse(0)
        }
      }
    }

    def findPhotoUrlById(photoId : Long) : Future[Option[String]] = {
      future {
        val res = photoDao.findById(photoId).map(_.url)
        if (res.isEmpty) log.error(s"requested a non-existent stock photo with id $photoId")
        res
      }
    }

    private def backfillBlockedContact(ownerId : Long, contactId : Long)(implicit session : Session) : Contact = {
      contacts.insertOperation(Contact(None, ownerId, contactId, Blocked))
    }

    //no check to see if the userId is present in here..only called by the function above
    private def getOtherUserId(conversation : Conversation, userId : Long) = {
      if (conversation.fromUser == userId) {
        conversation.toUser
      } else {
        conversation.fromUser
      }
    }
  }
}
