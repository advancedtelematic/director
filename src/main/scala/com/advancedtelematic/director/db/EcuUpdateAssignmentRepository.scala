package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.QueueResponse
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceUpdateAssignment, EcuUpdateAssignment}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

trait EcuUpdateAssignmentRepositorySupport {
  def ecuUpdateAssignmentRepository(implicit db: Database, ec: ExecutionContext) = new EcuUpdateAssignmentRepository()
}

protected class EcuUpdateAssignmentRepository()(implicit val db: Database, val ec: ExecutionContext)
  extends DeviceUpdateAssignmentRepositorySupport {

  import Schema.ecuUpdateAssignments

  protected [db] def persistAction(
    namespace: Namespace, deviceId: DeviceId, version: Int, targets: Map[EcuIdentifier, CustomImage]
  ): DBIO[Unit] = {

    val act = (ecuUpdateAssignments ++= targets.map {
      case (ecuId, customImage) => EcuUpdateAssignment(namespace, deviceId, ecuId, version, customImage)
    })

    act.map(_ => ()).transactionally
  }

  protected [db] def fetchAction(namespace: Namespace, deviceId: DeviceId, version: Int): DBIO[Map[EcuIdentifier, CustomImage]] =
    ecuUpdateAssignments
      .filter(_.namespace === namespace)
      .filter(_.deviceId === deviceId)
      .filter(_.version === version)
      .map { ecuTarget => ecuTarget.ecuId -> ecuTarget.customImage }
      .result
      .map(_.toMap)

  def fetch(namespace: Namespace, deviceId: DeviceId, version: Int): Future[Map[EcuIdentifier, CustomImage]] =
    db.run(fetchAction(namespace, deviceId, version))

  def fetchQueue(namespace: Namespace, deviceId: DeviceId): Future[Seq[QueueResponse]] = db.run {
    def queueResult(updateAssignment: DeviceUpdateAssignment): DBIO[QueueResponse] = for {
      targets <- fetchAction(namespace, deviceId, updateAssignment.version)
    } yield QueueResponse(updateAssignment.correlationId, targets, updateAssignment.served)

    val versionOfDevice: DBIO[Int] = Schema.deviceCurrentTarget
      .filter(_.device === deviceId)
      .map(_.deviceCurrentTarget)
      .result
      .failIfMany
      .map(_.getOrElse(0))

    for {
      currentVersion <- versionOfDevice
      updates <- deviceUpdateAssignmentRepository.fetchAllAfterAction(namespace, deviceId, currentVersion)
      queue <- DBIO.sequence(updates.map(queueResult))
    } yield queue
  }
}

