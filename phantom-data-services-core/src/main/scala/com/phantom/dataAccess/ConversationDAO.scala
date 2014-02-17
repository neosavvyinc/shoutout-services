package com.phantom.dataAccess

/**
 * Created with IntelliJ IDEA.
 * User: aparrish
 * Date: 1/7/14
 * Time: 9:35 PM
 * To change this template use File | Settings | File Templates.
 */

import scala.slick.session.Database
import com.phantom.model.{ FeedEntry, Conversation }
import scala.concurrent.{ Future, ExecutionContext, future }
import com.phantom.ds.framework.Logging
import com.phantom.ds.framework.exception.PhantomException

class ConversationDAO(dal : DataAccessLayer, db : Database)(implicit ec : ExecutionContext)
    extends BaseDAO(dal, db)
    with Logging {

  import dal._
  import dal.profile.simple._

  //ONLY USED BY TESTS
  def insert(conversation : Conversation) : Conversation = {
    db.withTransaction { implicit session =>
      val id = ConversationTable.forInsert.insert(conversation)
      conversation.copy(id = Some(id))
    }
  }

  def insertAllOperation(conversations : Seq[Conversation])(implicit session : Session) : Seq[Conversation] = {
    log.trace(s"inserting $conversations")
    val b = ConversationTable.forInsert.insertAll(conversations : _*)
    b.zip(conversations).map {
      case (id, conversation) =>
        conversation.copy(id = Some(id))
    }
  }

  //ONLY USED BY TESTS
  def findByFromUserId(fromUserId : Long) : List[Conversation] = {
    db.withSession { implicit session =>
      val items = Query(ConversationTable) filter { _.fromUser === fromUserId }
      items.list()
    }
  }

  //ONLY USED BY TESTS
  def findById(conversationId : Long) : Future[Conversation] = {
    future {
      db.withSession { implicit session =>
        val q = Query(ConversationTable) filter { _.id === conversationId }
        q.firstOption
          .map((c : Conversation) => c)
          .getOrElse(throw PhantomException.nonExistentConversation)
      }
    }
  }

  def swapConversationsOperation(sourceUser : Long, desUser : Long)(implicit session : Session) : Int = {
    val q = for { c <- ConversationTable if c.toUser === sourceUser } yield c.toUser
    q.update(desUser)
  }

  private val byIdAndUserQuery = for {
    (conversationId, userId) <- Parameters[(Long, Long)]
    c <- ConversationTable if (c.id is conversationId) && ((c.fromUser is userId) || (c.toUser is userId))
  } yield c

  def findByIdAndUserOperation(conversationId : Long, userId : Long)(implicit session : Session) : Option[Conversation] = {
    byIdAndUserQuery(conversationId, userId).firstOption
  }

  //ONLY USED BY TESTS
  def updateById(conversation : Conversation) : Int = {
    db.withSession { implicit session =>
      val updateQuery = Query(ConversationTable) filter { _.id === conversation.id }
      updateQuery.update(conversation)
    }
  }

  def findConversationsAndItems(userId : Long) : Future[List[FeedEntry]] = {
    future {
      db.withSession { implicit session =>
        val conversationPairs = (for {
          c <- ConversationTable
          ci <- ConversationItemTable if c.id === ci.conversationId && (c.fromUser === userId || c.toUser === userId)
        } yield (c, ci)).sortBy(_._2.createdDate.desc).list()

        conversationPairs.groupBy(_._1).map {
          case (convo, cItem) => FeedEntry(convo, cItem.map(_._2))
        }.toList
      }
    }
  }
}

