package com.advancedtelematic.director.db

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.DeviceUpdateAssignment
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

class CancelUpdate(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceUpdateAssignmentRepositorySupport
    with EcuUpdateAssignmentRepositorySupport
    with DeviceRepositorySupport {
  private def act(namespace: Namespace, device: DeviceId): DBIO[Option[DeviceUpdateAssignment]] = {
    for {
      current <- deviceRepository.getCurrentVersionAction(device).map(_.getOrElse(0))
      queuedItems = Schema.deviceUpdateAssignments.filter(_.deviceId === device).filter(_.version > current)
      // we can cancel if none of them have been served
      cantCancel <- queuedItems.map(_.served).filter(identity).exists.result
      res <- if (cantCancel) {
        DBIO.successful(None)
      } else for {
        latestVersion <- deviceUpdateAssignmentRepository.fetchLatestAction(namespace, device)
        assignment <- deviceUpdateAssignmentRepository.fetchAction(namespace, device, latestVersion)
        nextTimestampVersion = latestVersion + 1
        _ <- deviceUpdateAssignmentRepository.persistAction(namespace, device, None, None, nextTimestampVersion)
        _ <- deviceRepository.updateDeviceVersionAction(device, nextTimestampVersion)
      } yield Some(assignment)
    } yield res
  }.transactionally


  def one(ns: Namespace, device: DeviceId): Future[DeviceUpdateAssignment] =
    db.run(act(ns, device)).flatMap {
      case Some(assignment) => FastFuture.successful(assignment)
      case None => FastFuture.failed(Errors.CouldNotCancelUpdate)
    }

  // returns the subset of devices that were canceled
  def several(ns: Namespace, devices: Seq[DeviceId]): Future[Seq[DeviceUpdateAssignment]] = db.run {
    DBIO.sequence(devices.map(act(ns,_))).map(_.flatten)
  }
}
