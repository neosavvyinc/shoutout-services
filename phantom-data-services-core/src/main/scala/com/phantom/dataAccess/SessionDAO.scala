package com.phantom.dataAccess

import scala.slick.session.Database
import com.phantom.ds.framework.Logging
import java.util.UUID
import com.phantom.model.{ PhantomSession, PhantomUser }
import scala.concurrent.{ ExecutionContext, Future, future }

class SessionDAO(dal : DataAccessLayer, db : Database)(implicit ec : ExecutionContext) extends BaseDAO(dal, db)
    with Logging {

  import dal._
  import dal.profile.simple._

  private val userBySessionId = for {
    id <- Parameters[UUID]
    (s, u) <- SessionTable innerJoin UserTable on ((sess, user) => sess.userId === user.id && sess.sessionId === id)
  } yield u

  private val byUserId = for {
    id <- Parameters[Long]
    (s, u) <- SessionTable innerJoin UserTable on ((sess, user) => sess.userId === user.id && sess.userId === id)
  } yield s

  //TODO future me
  def findFromSession(session : UUID) : Option[PhantomUser] = {
    db.withSession { implicit s =>
      userBySessionId(session).firstOption
    }
  }

  def existingSession(userId : Long) : Future[Option[PhantomSession]] = {
    future {
      db.withSession { implicit session =>
        byUserId(userId).firstOption
      }
    }
  }

  def createSession(session : PhantomSession) : Future[PhantomSession] = {
    future {
      db.withTransaction { implicit s =>
        SessionTable.insert(session)
        session
      }
    }
  }

  def removeSession(sessionId : UUID) : Future[Int] = {
    log.trace(s"deleting $sessionId")
    future {
      db.withTransaction { implicit session =>
        Query(SessionTable).where(_.sessionId === sessionId).delete
      }
    }
  }

}