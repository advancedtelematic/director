package com.advancedtelematic.director.daemon

import akka.actor.Scheduler
import com.advancedtelematic.director.data.Messages.DeviceManifestReported
import com.advancedtelematic.director.db.DeviceManifestRepositorySupport
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceManifestReportedListener()(implicit val db: Database, val ec: ExecutionContext, val scheduler: Scheduler)
  extends MsgOperation[DeviceManifestReported] with DeviceManifestRepositorySupport {

  override def apply(msg: DeviceManifestReported): Future[Unit] = {
    deviceManifestRepository.createOrUpdate(msg.deviceId, msg.manifest.signed, msg.receivedAt)
  }
}
