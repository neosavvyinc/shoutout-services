package com.phantom.ds

import spray.testkit.Specs2RouteTest
import spray.json._
import spray.httpx.SprayJsonSupport._
import spray.http.StatusCodes._
import org.specs2.mutable.{ After, Specification }
import org.specs2.matcher.MatchResult
import com.phantom.ds.framework.httpx.{ Failure, PhantomJsonProtocol }

trait PhantomEndpointSpec extends PhantomJsonProtocol with After {
  this : Specification with Specs2RouteTest =>

  def fromPayload[T](implicit format : JsonFormat[T]) : T = {

    val raw = responseAs[JsObject]
    val jsVal = raw.fields.getOrElse("payload", throw new Exception("malformed response, no payload detected"))
    format.read(jsVal)
  }

  def assertPayload[T](f : (T => MatchResult[_]))(implicit format : JsonFormat[T]) : MatchResult[_] = {
    status == OK
    f(fromPayload[T])

  }

  def assertFailure(code : Int) : MatchResult[Int] = {
    status == OK
    val response = responseAs[Failure]
    response.errorCode must be equalTo code
  }

  def after : Any = system.shutdown _
}
