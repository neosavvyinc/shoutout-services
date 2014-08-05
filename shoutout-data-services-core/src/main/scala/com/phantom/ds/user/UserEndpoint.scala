package com.phantom.ds.user

import spray.http.MediaTypes._
import com.phantom.model._
import com.phantom.ds.framework.httpx._
import spray.json._
import com.phantom.ds.{ BasicCrypto, DataHttpService }
import com.phantom.ds.framework.auth.{ EntryPointAuthenticator, RequestAuthenticator }
import java.util.UUID
import scala.concurrent.{ Await, Future }
import spray.http.StatusCodes

trait UserEndpoint extends DataHttpService with PhantomJsonProtocol with BasicCrypto {
  this : RequestAuthenticator with EntryPointAuthenticator =>

  val userService = UserService()
  val users = "users"

  /**
   * This should accept a email and password request
   *
   * @return - session or user id for the record
   */
  def loginEmail = pathPrefix(users / "login" / "email") {
    post {
      entity(as[UserLogin]) { loginRequest =>
        respondWithMediaType(`application/json`) {
          complete(userService.login(loginRequest))
        }
      }
    }
  }

  /**
   * This should log you in with Facebook if we have a record
   * for you, otherwise it should create a record
   * @return - session or user id for the record
   */
  def loginFacebook = ???

  /**
   * This should accept
   * @return
   */
  def registerEmail = pathPrefix(users / "register") {
    post {
      respondWithMediaType(`application/json`)
      entity(as[UserRegistrationRequest]) {
        reg =>
          log.trace(s"registering $reg")
          complete(userService.register(reg))
      }
    }
  }

  def simpleHello = pathPrefix(users) {
    get {
      respondWithMediaType(`application/json`) {
        complete {
          StatusCodes.OK
        }
      }
    }
  }

  val userRoute = simpleHello ~ loginEmail ~ registerEmail
}
