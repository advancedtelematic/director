package com.advancedtelematic.director.db

import akka.Done
import com.advancedtelematic.director.data.DataType.{DeviceCurrentTarget, FileCacheRequest}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.util.NamespaceTag.NamespaceTag
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

trait SetVersion {
  def setCampaign(device: DeviceId, version: Int)
                 (implicit db: Database, ec: ExecutionContext, ns: NamespaceTag): Future[Done] = db.run {
    (Schema.fileCacheRequest += FileCacheRequest(Namespace(ns.value), version, device, None, FileCacheRequestStatus.PENDING, version))
      .map(_ => Done)
  }

  def setDeviceVersion(device: DeviceId, version: Int)
                      (implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    (Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, version)))
      .map(_ => Done)
  }
}
