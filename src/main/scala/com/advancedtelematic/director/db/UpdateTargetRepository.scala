package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.{QueueResponse}
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceUpdateTarget, EcuTarget}
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libtuf.data.TufDataType.{TargetFilename}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.jdbc.MySQLProfile.api._

import Errors._

trait UpdateTargetRepositorySupport {
  def updateTargetRepository(implicit db: Database, ec: ExecutionContext) = new UpdateTargetRepository()
}

protected class UpdateTargetRepository()(implicit db: Database, ec: ExecutionContext) {

  protected [db] def updateDeviceTargetsAction(device: DeviceId, correlationId: Option[CorrelationId], updateId: Option[UpdateId], version: Int): DBIO[DeviceUpdateTarget] = {
    val target = DeviceUpdateTarget(device, correlationId, updateId, version, inFlight = false)

    (Schema.deviceTargets += target)
      .map(_ => target)
      .handleIntegrityErrors(ConflictingTarget)
  }

  protected [db] def updateExistsAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Boolean] =
    Schema.deviceTargets
      .filter(_.device === device)
      .filter(_.version === version)
      .exists
      .result

  def updateExists(namespace: Namespace, device: DeviceId, version: Int): Future[Boolean] =
    db.run(updateExistsAction(namespace, device, version))

  protected [db] def getLatestScheduledVersion(namespace: Namespace, deviceId: DeviceId): DBIO[Int] =
    Schema.deviceTargets
      .filter(_.device === deviceId)
      .map(_.version)
      .forUpdate
      .max
      .result
      .failIfNone(NoTargetsScheduled)

  protected [db] def fetchDeviceUpdateTargetAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[DeviceUpdateTarget] =
    Schema.deviceTargets
      .filter(_.device === device)
      .filter(_.version === version)
      .result
      .failIfMany
      .failIfNone(NoTargetsScheduled)


  def findQueue(namespace: Namespace, device: DeviceId): Future[Seq[QueueResponse]] = db.run {
    def queueResult(updateTarget: DeviceUpdateTarget): DBIO[QueueResponse] = for {
      targets <- fetchTargetVersionAction(namespace, device, updateTarget.targetVersion)
    } yield QueueResponse(updateTarget.correlationId, targets, updateTarget.inFlight)

    val versionOfDevice: DBIO[Int] = Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .result
      .failIfMany
      .map(_.getOrElse(0))

    def allUpdatesScheduledAfter(fromVersion: Int): DBIO[Seq[DeviceUpdateTarget]] =
      Schema.deviceTargets
        .filter(_.device === device)
        .filter(_.version > fromVersion)
        .sortBy(_.version)
        .result

    for {
      currentVersion <- versionOfDevice
      updates <- allUpdatesScheduledAfter(currentVersion)
      queue <- DBIO.sequence(updates.map(queueResult))
    } yield queue
  }

  protected [db] def storeTargetVersion(
    namespace: Namespace, deviceId: DeviceId, version: Int, targets: Map[EcuSerial, CustomImage]
  ): DBIO[Unit] = {

    // TODO: deviceId
    val act = (Schema.ecuUpdateTarget
      ++= targets.map{ case (ecuSerial, customImage) => EcuTarget(namespace, version, ecuSerial, customImage)})

    act.map(_ => ()).transactionally
  }

  protected [db] def updateTargetAction(namespace: Namespace, deviceId: DeviceId, targets: Map[EcuSerial, CustomImage]): DBIO[Int] = for {
    version <- getLatestScheduledVersion(namespace, deviceId).asTry.flatMap {
      case Success(x) => DBIO.successful(x)
      case Failure(NoTargetsScheduled) => DBIO.successful(0)
      case Failure(ex) => DBIO.failed(ex)
    }
    new_version = version + 1
    _ <- storeTargetVersion(namespace, deviceId, new_version, targets)
  } yield new_version

  def updateTarget(namespace: Namespace, device: DeviceId, targets: Map[EcuSerial, CustomImage]): Future[Int] = db.run {
    updateTargetAction(namespace, device, targets).transactionally
  }

  // skips any targets with version > sourceVersion
  protected [db] def copyTargetsAction(namespace: Namespace, device: DeviceId, sourceVersion: Int, targetVersion: Int): DBIO[Unit] =
    Schema.ecuUpdateTarget
      .filter(_.namespace === namespace)
      .filter(_.deviceId === device)
      .result
      .flatMap { ecuTargets =>
        Schema.ecuTargets ++= ecuTargets.map(_.copy(version = targetVersion))
      }.map(_ => ())

  private def contains(map: Map[EcuSerial, TargetFilename], pair: (Rep[EcuSerial], Rep[TargetFilename])) =
    map.map { case (key, value) =>
      pair._1 === key && pair._2 === value
    }.reduce(_ || _)

  // skips any targets in "failed"
  protected [db] def copyTargetsAction(namespace: Namespace, device: DeviceId, sourceVersion: Int, targetVersion: Int,
                                       failed: Map[EcuSerial, TargetFilename]): DBIO[Unit] =
    Schema.ecuUpdateTarget
      .filter(_.namespace === namespace)
      .filter(_.deviceId === device)
      // remove the failed ecuTargets
      .filterNot { ecuTarget =>
        contains(failed, ecuTarget.ecuId -> ecuTarget.filepath)
      }
      .result
      .flatMap { ecuTargets =>
        Schema.ecuTargets ++= ecuTargets.map(_.copy(version = targetVersion))
      }.map(_ => ())

  protected [db] def fetchTargetVersionAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Map[EcuSerial, CustomImage]] =
    Schema.ecuUpdateTarget
      .filter(_.namespace === namespace)
      .filter(_.deviceId === device)
      .map{ecuTarget => ecuTarget.ecuId -> ecuTarget.customImage}
      .result
      .map(_.toMap)

  def fetchTargetVersion(namespace: Namespace, device: DeviceId, version: Int): Future[Map[EcuSerial, CustomImage]] =
    db.run(fetchTargetVersionAction(namespace, device, version))
}

