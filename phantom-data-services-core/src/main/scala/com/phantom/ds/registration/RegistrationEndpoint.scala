package com.phantom.ds.registration

import com.phantom.ds.DataHttpService
import com.phantom.ds.framework.httpx.PhantomJsonProtocol
import com.phantom.ds.framework.auth.EntryPointAuthenticator
import spray.http.MediaTypes._
import com.phantom.model.{ RegistrationVerification, UserRegistration }

trait RegistrationEndpoint extends DataHttpService
    with PhantomJsonProtocol { this : EntryPointAuthenticator =>

  val registrationService = RegistrationService()

  val registrationRoute =
    pathPrefix("users" / "register") {
      authenticate(enter _) {
        bool =>
          post {
            respondWithMediaType(`application/json`)
            entity(as[UserRegistration]) {
              reg =>
                log.trace(s"registering $reg")
                complete(registrationService.register(reg))
            }
          }
      }
    } ~
      pathPrefix("users" / "verification") {  //lack of auth..this is twilio based...TODO: investigate security options here
        post {
          formFields(
            'AccountSid.as[String],
            'MessageSid.as[String],
            'From.as[String],
            'To.as[String],
            'Body.as[String],
            'NumMedia.as[Int]) {
              (messageSid, accountSid, from, to, body, numMedia) =>
                complete {
                  registrationService.verifyRegistration(
                    RegistrationVerification(messageSid, accountSid, from, to, body, numMedia))
                }
            }
        }

      }
}