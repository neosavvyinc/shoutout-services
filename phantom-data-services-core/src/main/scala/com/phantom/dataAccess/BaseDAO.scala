package com.phantom.dataAccess

import scala.slick.session.Database

class BaseDAO(name : String, dal : DataAccessLayer, db : Database) {
  import scala.slick.session.Session

  implicit val implicitSession : Session = db.createSession
}
