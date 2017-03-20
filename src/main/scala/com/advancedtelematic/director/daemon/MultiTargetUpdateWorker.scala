package com.advancedtelematic.director.daemon

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.MultiTargetUpdate
import com.advancedtelematic.director.db.{MultiTargetUpdatesRepositorySupport, Errors => DBErrors}
import org.slf4j.LoggerFactory
import slick.driver.MySQLDriver.api._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

object MultiTargetUpdateWorker extends MultiTargetUpdatesRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def action(mtu: MultiTargetUpdate)(implicit db: Database, ec: ExecutionContext): Future[Done] = {

    val action = async {
      _log.info(s"received event MultiTargetUpdate ${mtu.id}")
      await(multiTargetUpdatesRepository.create(mtu))
      Done
    }

    action.recoverWith {
      case DBErrors.ConflictingMultiTargetUpdate =>
        _log.info(s"Ignoring multi-target update for ${mtu.id} since an update with this id already exists.")
        FastFuture.successful(Done)
    }
  }
}
