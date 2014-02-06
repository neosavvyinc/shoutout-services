package com.phantom.ds.user

import spray.http.MediaTypes._
import com.phantom.model._
import com.phantom.ds.framework.httpx._
import spray.json._
import com.phantom.ds.DataHttpService
import com.phantom.ds.framework.auth.{ EntryPointAuthenticator, RequestAuthenticator }
import spray.http.StatusCodes

trait UserEndpoint extends DataHttpService with PhantomJsonProtocol {
  this : RequestAuthenticator with EntryPointAuthenticator =>

  val userService = UserService()

  val userRoute =
    pathPrefix("users" / "login") {
      authenticate(enter _) {
        bool =>
          post {
            respondWithMediaType(`application/json`)
            entity(as[UserLogin]) {
              reg =>
                complete(userService.login(reg))
            }
          }
      }
    } ~
      pathPrefix("users" / "logout") {
        authenticate(request _) { user =>
          get {
            parameter('sessionId) { session =>
              respondWithMediaType(`application/json`) {
                complete(userService.logout(session))
              }
            }
          }
        }
      } ~
      pathPrefix("users" / LongNumber / "contacts") { id =>
        authenticate(request _) { user =>
          post {
            respondWithMediaType(`application/json`) {
              entity(as[Map[String, List[String]]]) { phoneNumbers =>
                complete {
                  phoneNumbers.isDefinedAt("numbers") match {
                    case true  => userService.updateContacts(id, phoneNumbers("numbers"))
                    case false => "Invalid Dictionary Key"
                  }
                }
              }
            }
          }
        }
      } ~
      pathPrefix("users" / LongNumber / "clearblocklist") { id =>
        post {
          respondWithMediaType(`application/json`) {
            complete(userService.clearBlockList(id))
          }
        }
      } ~
      pathPrefix("users" / LongNumber) { id =>
        authenticate(request _) { user =>
          get {
            respondWithMediaType(`application/json`) {
              complete(userService.findById(id))
            }
          }
        }
      } ~
      pathPrefix("users" / "pushNotifier") {
        post {
          entity(as[SessionIDWithPushNotifier]) {
            sessionIDWithNotifier =>
              complete {
                StatusCodes.OK
              }
          }

        }
      }
}
