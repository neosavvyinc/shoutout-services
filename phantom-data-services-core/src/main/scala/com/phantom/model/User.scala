package com.phantom.model

import org.joda.time.LocalDate
import scala.slick.direct.AnnotationMapper.column
import scala.slick.driver.BasicDriver.Table
import java.sql.Date
import scala.slick.lifted.ColumnOption.DBType

case class UserRegistration(email : String,
                            birthday : LocalDate,
                            password : String)

case class UserLogin(email : String,
                     password : String)

case class ClientSafeUserResponse(email : String,
                                  phoneNumber : String,
                                  birthday : LocalDate,
                                  newPictureReceivedNotification : Boolean,
                                  soundsNotification : Boolean)

case class PhantomUserDeleteMe(id : String)

case class UserInsert(email : String,
                      birthday : LocalDate,
                      saltyHash : String,
                      active : Boolean)

// TO DO
// secret client-facing/obfuscated user id?
case class PhantomUser(id : Option[Long],
                       email : String,
                       birthday : String,
                       active : Boolean,
                       phoneNumber : String)

trait UserComponent {

  object UserTable extends Table[PhantomUser]("USERS") {
    def id = column[Long]("ID", O.PrimaryKey, O.AutoInc)
    def email = column[String]("EMAIL", DBType("VARCHAR(256)"))
    def birthday = column[String]("BIRTHDAY")
    def active = column[Boolean]("ACTIVE")
    def phoneNumber = column[String]("PHONE_NUMBER")

    def * = id.? ~ email ~ birthday ~ active ~ phoneNumber <> (PhantomUser, PhantomUser.unapply _)

  }
}

