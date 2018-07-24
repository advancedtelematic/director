package com.advancedtelematic.director.db

import akka.Done
import com.advancedtelematic.director.data.DataType.{DeviceCurrentTarget, DeviceUpdateTarget}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

trait SetVersion {
  def setCampaign(device: DeviceId, version: Int)
                 (implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    (Schema.deviceTargets += DeviceUpdateTarget(device, None, version, false))
      .map(_ => Done)
  }

  def setDeviceVersion(device: DeviceId, version: Int)
                      (implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    (Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, version)))
      .map(_ => Done)
  }
}
