package com.phantom.ds.registration

import org.specs2.mutable.Specification
import com.phantom.ds.{ TestUtils, PhantomEndpointSpec }
import spray.testkit.Specs2RouteTest
import com.phantom.ds.framework.auth.PassThroughEntryPointAuthenticator
import com.phantom.ds.dataAccess.BaseDAOSpec
import org.joda.time.LocalDate
import scala.concurrent.duration
import java.util.concurrent.TimeUnit
import com.phantom.model._
import com.phantom.model.RegistrationResponse
import com.phantom.model.UserRegistration
import spray.http.StatusCodes._
import spray.http.FormData
import scala.concurrent.ExecutionContext.Implicits.global

class RegistrationEndpointSpec extends Specification
    with PhantomEndpointSpec
    with Specs2RouteTest
    with PassThroughEntryPointAuthenticator
    with RegistrationEndpoint
    with BaseDAOSpec
    with TestUtils {

  def actorRefFactory = system

  val birthday = LocalDate.parse("1981-08-10")

  sequential

  "Registration Service" should {

    "be able to register a user" in withSetupTeardown {

      implicit val routeTestTimeout = RouteTestTimeout(duration.FiniteDuration(5, TimeUnit.SECONDS))

      val newUser = UserRegistration("adamparrish@something.com", birthday, "mypassword")
      Post("/users/register", newUser) ~> registrationRoute ~> check {
        assertPayload[RegistrationResponse] { response =>
          response.sessionUUID must not be null
          response.verificationUUID must not be null
        }
      }
    }

    "make sure registering a user with all caps emails makes them LOWER" in withSetupTeardown {

      implicit val routeTestTimeout = RouteTestTimeout(duration.FiniteDuration(5, TimeUnit.SECONDS))

      val newUser = UserRegistration("ALLCAPS@ALLCAPSDOMAIN.CX", birthday, "mypassword")
      Post("/users/register", newUser) ~> registrationRoute ~> check {
        assertPayload[RegistrationResponse] { response =>
          val email = getUser(response.verificationUUID).email
          email.get must be matching "allcaps@allcapsdomain.cx"
        }
      }

    }

    "fail if registering a user with a duplicate email" in withSetupTeardown {
      val newUser = UserRegistration("adamparrish@something.com", birthday, "somethingelse")
      createVerifiedUser(newUser.email, newUser.password)

      Post("/users/register", newUser) ~> registrationRoute ~> check {
        assertFailure(101)
      }
    }

    "fail if registering user doesn't meet password complexity" in withSetupTeardown {
      val newUser = UserRegistration("adamparrish@something.com", birthday, "s")
      Post("/users/register", newUser) ~> registrationRoute ~> check {
        assertFailure(105)
      }
    }

    "be able to verify a registration" in withSetupTeardown {

      val user = createUnverifiedUser("email@email.com", "password")
      val regResponse = reg("pre", user.uuid.toString, "post")

      val formData = FormData(Map("AccountSid" -> regResponse.accountSid,
        "MessageSid" -> regResponse.messageSid,
        "From" -> "987654321",
        "To" -> regResponse.to,
        "Body" -> regResponse.body,
        "NumMedia" -> regResponse.numMedia.toString))

      Post("/users/verification", formData) ~> registrationRoute ~> check {
        status == OK
        val updatedUser = phantomUsersDao.find(user.id.get).get
        updatedUser.status must be equalTo Verified
        updatedUser.phoneNumber must be equalTo Some("987654321")
      }
    }

    "be able to verify a registration with nexmo" in withSetupTeardown {

      val user = createUnverifiedUser("email@email.com", "password")
      val regResponse = nexMoReg("19999999999", "19197419597", "pre", user.uuid.toString, "post")

      Get(s"/users/verification?messageId=${regResponse.messageSid}&to=${regResponse.to}&msisdn=${regResponse.from}&text=${regResponse.body}") ~> registrationRoute ~> check {
        status == OK
        val updatedUser = phantomUsersDao.find(user.id.get).get
        updatedUser.status must be equalTo Verified
        updatedUser.phoneNumber must be equalTo Some("+19197419597")
      }

    }

    "be able to convert a StubUser" in withSetupTeardown {
      val fromUser = createVerifiedUser("n@n.com", "password").id.get
      val user = createUnverifiedUser("email@email.com", "password")
      val stubUser = createStubUser("987654321")
      val stubConversation = conversationDao.insert(Conversation(None, stubUser.id.get, fromUser, "9197419597"))
      conversationItemDao.insertAll(Seq(ConversationItem(None, stubConversation.id.get, "url", "text", stubConversation.toUser, stubConversation.fromUser)))
      val regResponse = reg("pre", user.uuid.toString, "post")

      val formData = FormData(Map("AccountSid" -> regResponse.accountSid,
        "MessageSid" -> regResponse.messageSid,
        "From" -> "987654321",
        "To" -> regResponse.to,
        "Body" -> regResponse.body,
        "NumMedia" -> regResponse.numMedia.toString))

      Post("/users/verification", formData) ~> registrationRoute ~> check {
        status == OK
        val updatedUser = phantomUsersDao.find(user.id.get).get
        updatedUser.status must be equalTo Verified
        updatedUser.phoneNumber must be equalTo Some("987654321")
        val stubUsers = phantomUsersDao.find(stubUser.id.get)
        stubUsers must beNone
        val stubConversations = getFullFeed(stubUser.id.get)
        stubConversations must beEmpty

        val conversations = getFullFeed(user.id.get)
        conversations.foreach {
          case FeedEntry(c, items) =>
            items must have size 1
            items.head.imageText must be equalTo "text"
            items.head.imageUrl must be equalTo "url"
            c.fromUser must be equalTo fromUser
            c.toUser must be equalTo user.id.get
        }
        conversations must have size 1
      }
    }

    "not allow for a phone number to be registered more than once" in withSetupTeardown {
      val user = createUnverifiedUser("email@email.com", "password")
      val user2 = createUnverifiedUser("email2@email.com", "password")
      val regResponse = reg("pre", user.uuid.toString, "post")
      val regResponse2 = reg("pre", user2.uuid.toString, "post")

      val formData = FormData(Map("AccountSid" -> regResponse.accountSid,
        "MessageSid" -> regResponse.messageSid,
        "From" -> "987654321",
        "To" -> regResponse.to,
        "Body" -> regResponse.body,
        "NumMedia" -> regResponse.numMedia.toString))

      val formData2 = FormData(Map("AccountSid" -> regResponse2.accountSid,
        "MessageSid" -> regResponse2.messageSid,
        "From" -> "987654321",
        "To" -> regResponse2.to,
        "Body" -> regResponse2.body,
        "NumMedia" -> regResponse2.numMedia.toString))

      Post("/users/verification", formData) ~> registrationRoute ~> check {
        status == OK
        val updatedUser = phantomUsersDao.find(user.id.get).get
        updatedUser.status must be equalTo Verified
        updatedUser.phoneNumber must be equalTo Some("987654321")
      }

      Post("/users/verification", formData2) ~> registrationRoute ~> check {
        status == OK
        val updatedUser = phantomUsersDao.find(user2.id.get).get
        updatedUser.status must be equalTo Unverified
        updatedUser.phoneNumber must be equalTo None
      }
    }
  }
}