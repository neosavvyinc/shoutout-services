package com.phantom.ds.conversation

import scala.concurrent.{ Future, ExecutionContext, future }
import com.phantom.model._
import com.phantom.dataAccess.DatabaseSupport
import com.phantom.ds.{ BasicCrypto, DSConfiguration }
import com.phantom.model.BlockUserByConversationResponse
import com.phantom.model.ConversationUpdateResponse
import com.phantom.model.Conversation
import com.phantom.model.ConversationItem
import com.phantom.model.ConversationInsertResponse
import com.phantom.ds.framework.Logging
import akka.actor.ActorRef
import com.phantom.ds.integration.twilio.SendInviteToStubUsers
import com.phantom.ds.integration.apple.AppleNotification
import com.phantom.ds.framework.exception.PhantomException
import scala.slick.session.Session
import java.util.UUID

import com.phantom.ds.integration.amazon.S3Service

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
                            image : Array[Byte]) : Future[ConversationUpdateResponse]

  def saveFileForConversationId(image : Array[Byte], conversationId : Long) : String

  def blockByConversationId(userId : Long, conversationId : Long) : Future[BlockUserByConversationResponse]

  def viewConversationItem(conversationItemId : Long, userId : Long) : Future[Boolean]

  def deleteConversation(userId : Long, conversationId : Long) : Future[Int]

  def deleteConversationItem(userId : Long, conversationItemId : Long) : Future[Int]

}

object ConversationService extends DSConfiguration with BasicCrypto {

  def apply(twilioActor : ActorRef, appleActor : ActorRef, s3Service : S3Service)(implicit ec : ExecutionContext) = new ConversationService with DatabaseSupport with Logging {

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
        (unconnectedUsers, connectedUsers) <- partitionMutuallyConnectedUsers(users, fromUserId)
        (newStubUsers, response) <- createStubUsersAndRoots(nonUsers, connectedUsers ++ stubUsers, unconnectedUsers, fromUserId, imageText, imageUrl)
        _ <- sendInvitations(stubUsers ++ newStubUsers)
        tokens <- getTokens(connectedUsers.map(_.id.get)) //TODO: These two lines need not be here..they are a single function
        _ <- sendNewConversationNotifications(connectedUsers, tokens)
      } yield response
    }

    private def getTokens(userIds : Seq[Long]) : Future[List[Option[String]]] = {
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

    /*
      How this works:
       first split users into 2 groups, users who enabled mutualContactsOnly and those who did not
       For the mutualcontactsOnly group, check their contacts to see if the fromuser is in their contacts
       Split the mutualContactsOnly group into users that are connected to the fromUser and those who are not
       disconnected users are returned as one set, and promiscuosUsers an connected users are merged and returned as
       anotehr group, since those are treated teh same(ie, all the to users get a conversation, awhereas the disconnected don't receive any notifications
     */
    private def partitionMutuallyConnectedUsers(users : Seq[PhantomUser], fromUserId : Long) = {
      future {
        db.withSession { implicit session : Session =>
          val (mutualContactsOnly, promiscuousUsers) = users.partition(_.mutualContactSetting)

          val userIds = mutualContactsOnly.map(_.id).flatten
          val mutuallyConnectedUsers = contacts.filterConnectedToContactOperation(userIds.toSet, fromUserId)

          val (connected, notConnected) = mutualContactsOnly.partition(_.id.exists(mutuallyConnectedUsers.contains(_)))
          (notConnected, connected ++ promiscuousUsers)

        }
      }
    }

    private def partition(phantomsF : Future[List[PhantomUser]], contactNumbers : Set[String]) : Future[(Set[String], Seq[PhantomUser])] = {
      phantomsF.map { users =>
        val existingNumbers = users.map(_.phoneNumber).flatten.toSet
        val nonUsers = contactNumbers.diff(existingNumbers)
        (nonUsers, users)
      }
    }

    private def createStubUsersAndRoots(numbers : Set[String], users : Seq[PhantomUser], unconnectedUsers : Seq[PhantomUser], fromUserId : Long, imageText : String, imageUrl : String) : Future[(Seq[PhantomUser], ConversationInsertResponse)] = {
      future {
        db.withTransaction { implicit session : Session =>
          val newUsers = phantomUsersDao.insertAllOperation(numbers.map(x => PhantomUser(None, UUID.randomUUID, None, None, None, false, Some(x), Stub, 0)).toSeq)
          val connectedCount = createConversations(users ++ newUsers, fromUserId, imageText, imageUrl, false)
          val unconnectedCount = createConversations(unconnectedUsers, fromUserId, imageText, imageUrl, true)
          (newUsers, ConversationInsertResponse(connectedCount + unconnectedCount))
        }
      }
    }

    private def createConversations(users : Seq[PhantomUser], fromUserId : Long, imageText : String, imageUrl : String, invisibleToReceiver : Boolean)(implicit session : Session) = {
      val conversations = users.map(x => Conversation(None, x.id.getOrElse(-1), fromUserId, x.phoneNumber.get))
      val createdConversations = conversationDao.insertAllOperation(conversations)
      conversationItemDao.insertAllOperation(createConversationItemRoots(createdConversations, fromUserId, imageText, imageUrl, invisibleToReceiver)).size
    }

    private def createConversationItemRoots(conversations : Seq[Conversation], fromUserId : Long, imageText : String, imageUrl : String, deletedByToUser : Boolean) : Seq[ConversationItem] = {
      conversations.map(x => ConversationItem(None, x.id.getOrElse(-1), imageUrl, imageText, x.toUser, fromUserId, toUserDeleted = deletedByToUser))
    }

    private def sendNewConversationNotifications(users : Seq[PhantomUser], tokens : Seq[Option[String]]) : Future[Unit] = {
      tokens.foreach { token =>
        log.debug(s">>>>>>> sending a push notification for a new conversation $users and $token")
      }

      future {
        users.foreach { user =>
          sendConversationNotifications(user, tokens)
        }
      }
    }

    private def sendConversationNotifications(user : PhantomUser, tokens : Seq[Option[String]]) : Future[Unit] = {
      future {
        log.debug(s"notifications are $tokens")

        tokens.foreach { token =>
          log.debug(s"User is $user and token is $token and nonEmpty is: $token.nonEmpty")
          if (token.nonEmpty && user.settingNewPicture) {
            log.debug(s"sending an apple notification to the apple actor")
            appleActor ! AppleNotification(user.settingSound, token)
          } else {
            log.error(s"sendConversationNotifications called with empty token")
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

    override def respondToConversation(loggedInUser : Long, conversationId : Long, imageText : String, image : Array[Byte]) : Future[ConversationUpdateResponse] = {
      future {
        db.withTransaction { implicit session =>
          val citem = for {
            conversation <- conversationDao.findByIdAndUserOperation(conversationId, loggedInUser)
          } yield conversationItemDao.insertOperation(
            ConversationItem(
              None,
              conversationId,
              saveFileForConversationId(
                image,
                conversationId),
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
                  tokens : List[Option[String]] <- tokensFuture
                  _ <- future {
                    user.map { u : PhantomUser =>
                      log.debug(s"sending an apple push notification for a response from a previous conversation item $u.id")
                      sendConversationNotifications(u, tokens)
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
      s3Service.saveImage(image, conversationId)
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
