package com.phantom.ds.dataAccess

import org.specs2.mutable.Specification
import com.phantom.dataAccess.DatabaseSupport
import org.specs2.specification.BeforeAfter
import com.phantom.model._
import org.joda.time.{ DateTimeZone, LocalDate }
import java.util.UUID
import com.phantom.ds.user.Passwords
import com.phantom.ds.TestUtils
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created with IntelliJ IDEA.
 * User: aparrish
 * Date: 1/9/14
 * Time: 9:34 PM
 * To change this template use File | Settings | File Templates.
 */
trait BaseDAOSpec extends Specification with DatabaseSupport with TestUtils {

  object withSetupTeardown extends BeforeAfter {
    def before {
      dataAccessLayer.drop(db)
      dataAccessLayer.create(db)
    }

    def after {
      dataAccessLayer.drop(db)
    }
  }

  //override def after : Any = source.close _

  def setupConversationItems(convId : Long) : List[ConversationItem] = {
    val item1 = new ConversationItem(
      None, convId, "image1Url", "image1Text"
    )
    val item2 = new ConversationItem(
      None, convId, "image1Url", "image1Text"
    )
    val item3 = new ConversationItem(
      None, convId, "image1Url", "image1Text"
    )
    List(item1, item2, item3)
  }

  def createVerifiedUser(email : String, password : String, phoneNumber : String = "") : PhantomUser = {
    val user = PhantomUser(None, UUID.randomUUID, Some(email), Some(Passwords.getSaltedHash(password)), Some(LocalDate.now(DateTimeZone.UTC)), true, Some(phoneNumber), false, false, 0, Verified)
    phantomUsersDao.insert(user)
  }

  def createUnverifiedUser(email : String, password : String) = {
    phantomUsersDao.insert(PhantomUser(None, UUID.randomUUID, Some(email), Some(Passwords.getSaltedHash(password)), Some(LocalDate.now(DateTimeZone.UTC)), true, None, false, false, 0, Unverified))
  }

  def createStubUser(phone : String, count : Int = 1) = {
    phantomUsersDao.insert(PhantomUser(None, UUID.randomUUID, None, None, Some(LocalDate.now(DateTimeZone.UTC)), true, Some(phone), false, false, count, Stub))
  }

  def createConversation(fromId : Long, toId : Long) : Conversation = {
    conversationDao.insert(Conversation(None, toId, fromId))
  }

  def insertTestUsers() {
    createVerifiedUser("aparrish@neosavvy.com", "password", "111111")
    createVerifiedUser("ccaplinger@neosavvy.com", "password", "222222")
    createVerifiedUser("tewen@neosavvy.com", "password", "333333")
    createVerifiedUser("dhamlett@neosavvy.com", "password", "444444")
    createVerifiedUser("nick.sauro@gmail.com", "password", "555555")
    createVerifiedUser("pablo.alonso@gmail.com", "password", "666666")
  }

  def insertUsersWithPhoneNumbersAndContacts() = {
    insertTestUsers()
    insertTestContacts()
  }

  def insertTestContacts() {
    contacts.insertAll(
      Seq(
        Contact(None, 1, 2),
        Contact(None, 1, 3),
        Contact(None, 1, 4)
      )
    )
  }

  def insertTestConversations() {

    val conv1 = new Conversation(None, 1, 2)
    val conv2 = new Conversation(None, 3, 4)
    val conv3 = new Conversation(None, 5, 6)
    conversationDao.insert(conv1)
    conversationDao.insert(conv2)
    conversationDao.insert(conv3)

  }

  def insertTestConverationsWithItems() {
    insertTestUsersAndConversations()

    val conv1item1 = new ConversationItem(None, 1, "imageUrl1", "imageText1")
    val conv1item2 = new ConversationItem(None, 1, "imageUrl2", "imageText2")
    val conv1item3 = new ConversationItem(None, 1, "imageUrl3", "imageText3")

    val conv2item1 = new ConversationItem(None, 2, "imageUrl1", "imageText1")
    val conv2item2 = new ConversationItem(None, 2, "imageUrl2", "imageText2")
    val conv2item3 = new ConversationItem(None, 2, "imageUrl3", "imageText3")

    await(conversationItemDao.insertAll(Seq(conv1item1, conv1item2, conv1item3, conv2item1, conv2item2, conv2item3)))
  }

  def insertTestUsersAndConversations() {
    insertTestUsers()
    insertTestConversations()
  }

}
