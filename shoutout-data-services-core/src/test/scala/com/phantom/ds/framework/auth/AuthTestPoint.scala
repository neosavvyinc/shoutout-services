package com.phantom.ds.framework.auth

import spray.routing.HttpService
import spray.http.StatusCodes
import scala.concurrent.ExecutionContext.Implicits.global

trait AuthTestPoint extends HttpService {
  this : RequestAuthenticator with EntryPointAuthenticator =>

  val testRoute =
    pathPrefix("test" / "protected") {
      get {
        authenticate(verified _) {
          user =>
            complete {
              StatusCodes.OK
            }
        }
      }
    } ~
      pathPrefix("test" / "entry") {
        post {
          authenticate(enter _) {
            b =>
              complete {
                StatusCodes.OK
              }
          }
        }
      } ~ pathPrefix("test" / "unverified") {
        get {
          authenticate(unverified _) {
            user =>
              complete {
                StatusCodes.OK
              }
          }
        }

      }

}