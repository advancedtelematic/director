package com.advancedtelematic.director.db

import akka.Done
import com.advancedtelematic.director.data.DataType.{DeviceCurrentTarget, DeviceUpdateAssignment}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

trait SetVersion {
  def setCampaign(namespace: Namespace, device: DeviceId, version: Int)
                 (implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    (Schema.deviceUpdateAssignments += DeviceUpdateAssignment(namespace, device, None, None, version, false))
      .map(_ => Done)
  }

  def setDeviceVersion(device: DeviceId, version: Int)
                      (implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    (Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, version)))
      .map(_ => Done)
  }
}
