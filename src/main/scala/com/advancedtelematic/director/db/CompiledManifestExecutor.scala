package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DbDataType.{DeviceKnownStatus, DeviceNewStatus, EcuTargetId}
import com.advancedtelematic.director.repo.DeviceRoleGeneration
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric._
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

class CompiledManifestExecutor()(implicit val db: Database, val ec: ExecutionContext) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  private def findStatusAction(deviceId: DeviceId): DBIO[DeviceKnownStatus] = {
    val io = for {
      assignments <- Schema.assignments.filter(_.deviceId === deviceId).result
      processed <- Schema.processedAssignments.filter(_.deviceId === deviceId).result
      ecuStatus <- Schema.ecus.filter(_.deviceId === deviceId).map(ecu => ecu.ecuSerial -> ecu.installedTarget).result
      primaryEcu <- Schema.devices.filter(_.id === deviceId).map(_.primaryEcu).result.head
      ecuTargetIds = ecuStatus.flatMap(_._2) ++ assignments.map(_.ecuTargetId)
      ecuTargets <- Schema.ecuTargets.filter(_.id.inSet(ecuTargetIds)).map { t => t.id -> t }.result
    } yield DeviceKnownStatus(deviceId, primaryEcu, ecuStatus.toMap, ecuTargets.toMap, assignments.toSet, processed.toSet)

    io
  }

  private def updateEcuAction(deviceId: DeviceId, ecuIdentifier: EcuIdentifier, installedTarget: Option[EcuTargetId]): DBIO[Unit] = {
    Schema.ecus
      .filter(_.deviceId === deviceId)
      .filter(_.ecuSerial === ecuIdentifier).map(_.installedTarget).update(installedTarget).map(_ => ())
  }

  private def updateStatusAction(deviceId: DeviceId, oldStatus: DeviceKnownStatus, newStatus: DeviceNewStatus): DBIO[Unit] = {
    val assignmentsToDelete = (oldStatus.currentAssignments -- newStatus.currentAssignments).map(_.ecuId)
    val newProcessedAssignments = newStatus.processedAssignments -- oldStatus.processedAssignments

    val changedEcuStatus = newStatus.ecuStatus.filter { case (ecuId, ecuTargetId) =>  oldStatus.ecuStatus.get(ecuId).flatten != ecuTargetId }
    val newEcuTargets = newStatus.ecuTargets -- oldStatus.ecuTargets.keys

    for {
      _ <- DBIO.sequence(newEcuTargets.values.map(Schema.ecuTargets.insertOrUpdate))
      _ <- DBIO.sequence(changedEcuStatus.map { case (ecu, target) => updateEcuAction(deviceId, ecu, target) })
      _ <- Schema.assignments.filter(_.deviceId === deviceId).filter(_.ecuId.inSet(assignmentsToDelete)).delete
      _ <- DBIO.sequence(newProcessedAssignments.map(Schema.processedAssignments += _).toList )
    } yield ()
  }

  private def dbActionFromTry[T](t: Try[T]): DBIO[T] = t match {
    case Success(v) => DBIO.successful(v)
    case Failure(ex) => DBIO.failed(ex)
  }

  def process(deviceId: DeviceId, compiledManifest: DeviceKnownStatus => Try[DeviceNewStatus]): Future[DeviceNewStatus] = {
    val io = for {
      initialStatus <- findStatusAction(deviceId)
      newStatus <- dbActionFromTry(compiledManifest.apply(initialStatus))
      _ = _log.debug(s"Updating device status to $newStatus")
      _ <- updateStatusAction(deviceId, initialStatus, newStatus)
    } yield newStatus

    db.run(io.transactionally)
  }
}
