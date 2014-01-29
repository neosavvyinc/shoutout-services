package com.phantom.ds.conversation

import org.specs2.mutable.{ After, Specification }
import com.phantom.dataAccess.DatabaseSupport
import akka.testkit.TestProbe
import com.phantom.ds.dataAccess.BaseDAOSpec
import com.phantom.ds.TestUtils
import com.phantom.ds.integration.apple.SendConversationNotification
import akka.actor.ActorRefFactory
import spray.testkit.Specs2RouteTest
import com.phantom.model.StubUser
import com.phantom.ds.integration.twilio.{ SendInvite, SendInviteToStubUsers }

/**
 * Created with IntelliJ IDEA.
 * User: aparrish
 * Date: 1/11/14
 * Time: 1:37 PM
 */
class ConversationServiceSpec extends Specification
    with DatabaseSupport
    with BaseDAOSpec
    with Specs2RouteTest
    with TestUtils
    with After {

  def actorRefFactory : ActorRefFactory = system

  val tProbe = TestProbe()
  val aProbe = TestProbe()
  val service = ConversationService(tProbe.ref, aProbe.ref)

  sequential

  "The Conversation Service" should {

    "start conversations with only phantom users" in withSetupTeardown {

      val starter = createVerifiedUser("starter@starter.com", "password")
      val user1 = createVerifiedUser("email@email.com", "password", "12345")
      val user2 = createVerifiedUser("email2@email.com", "password", "56789")
      val results = await(service.startConversation(starter.id.get, Set("12345", "56789"), "text", "url"))

      results.createdCount must beEqualTo(2)

      val userIds = Seq(user1.id, user2.id).flatten
      val user1Conversation = conversationDao.findConversationsAndItems(starter.id.get)

      aProbe.expectMsg(SendConversationNotification(Seq(user1, user2)))
      tProbe.expectNoMsg()

      user1Conversation.foreach {
        case (c, items) =>
          items must have size 1
          items.head.imageText must beEqualTo("text")
          items.head.imageUrl must beEqualTo("url")
          c.toUser must beOneOf(userIds : _*)
      }
      user1Conversation must have size 2
    }

    "start conversations with only stub users" in withSetupTeardown {
      val stubUsers = await(stubUsersDao.insertAll(Seq(StubUser(None, "123", 0), StubUser(None, "456", 0))))
      val starter = createVerifiedUser("starter@starter.com", "password")
      val results = await(service.startConversation(starter.id.get, Set("123", "456"), "text", "url"))

      results.createdCount must beEqualTo(2)

      val userIds = stubUsers.map(_.id.get)

      val startedStubs = await(stubConversationsDao.findByFromUserId(starter.id.get))

      tProbe.expectMsg(SendInviteToStubUsers(stubUsers))
      aProbe.expectNoMsg()

      startedStubs.foreach { x =>
        x.toStubUser must beOneOf(userIds : _*)
        x.imageText must beEqualTo("text")
        x.imageUrl must beEqualTo("url")
      }

      startedStubs must have size 2

    }

    "start conversations with only unidentified users " in withSetupTeardown {
      val starter = createVerifiedUser("starter@starter.com", "password")
      val results = await(service.startConversation(starter.id.get, Set("123", "456"), "text", "url"))

      tProbe.expectMsg(SendInvite(Set("123", "456"), starter.id.get, "text", "url"))
      aProbe.expectNoMsg()

      results.createdCount must beEqualTo(0)
    }

    "start conversations with a mix of all three types of users" in withSetupTeardown {
      val starter = createVerifiedUser("starter@starter.com", "password")
      val user1 = createVerifiedUser("email@email.com", "password", "12")
      val user2 = createVerifiedUser("email2@email.com", "password", "34")
      val stubUsers = await(stubUsersDao.insertAll(Seq(StubUser(None, "56", 0), StubUser(None, "78", 0))))
      val nums = Set("12", "34", "56", "78", "90", "09")
      val results = await(service.startConversation(starter.id.get, nums, "text", "url"))

      val user1Conversation = conversationDao.findConversationsAndItems(starter.id.get)
      val userIds = Seq(user1.id, user2.id).flatten

      user1Conversation.foreach {
        case (c, items) =>
          items must have size 1
          items.head.imageText must beEqualTo("text")
          items.head.imageUrl must beEqualTo("url")
          c.toUser must beOneOf(userIds : _*)
      }

      val startedStubs = await(stubConversationsDao.findByFromUserId(starter.id.get))
      val stubUserIds = stubUsers.map(_.id.get)

      startedStubs.foreach { x =>
        x.toStubUser must beOneOf(stubUserIds : _*)
        x.imageText must beEqualTo("text")
        x.imageUrl must beEqualTo("url")
      }

      aProbe.expectMsg(SendConversationNotification(Seq(user1, user2)))
      tProbe.expectMsgAnyOf(SendInvite(Set("09", "90"), starter.id.get, "text", "url"), SendInviteToStubUsers(stubUsers))
      tProbe.expectMsgAnyOf(SendInvite(Set("09", "90"), starter.id.get, "text", "url"), SendInviteToStubUsers(stubUsers))
      results.createdCount must beEqualTo(4)

    }

    "not send invitations to stub users if their invitation count is maxed out" in withSetupTeardown {
      await(stubUsersDao.insertAll(Seq(StubUser(None, "888", 3), StubUser(None, "999", 3))))
      val starter = createVerifiedUser("starter@starter.com", "password")
      val results = await(service.startConversation(starter.id.get, Set("888", "999"), "text", "url"))

      aProbe.expectNoMsg()
      tProbe.expectNoMsg()
      results.createdCount must beEqualTo(2)
    }

  }

  def after : Any = system.shutdown _
}
