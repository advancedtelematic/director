package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.DeviceUpdateAssignment
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.director.db.SlickMapping._
import Errors._

trait DeviceUpdateAssignmentRepositorySupport {
  def deviceUpdateAssignmentRepository(implicit db: Database, ec: ExecutionContext) =
    new DeviceUpdateAssignmentRepository()
}

protected class DeviceUpdateAssignmentRepository()(implicit db: Database, ec: ExecutionContext) {
  import Schema.deviceUpdateAssignments

  def find(namespace: Namespace, deviceId: DeviceId, version: Int): Future[Option[DeviceUpdateAssignment]] = db.run {
    findAction(namespace, deviceId, version)
  }

  protected [db] def existsAction(namespace: Namespace, deviceId: DeviceId, version: Int): DBIO[Boolean] =
    findAction(namespace, deviceId, version).map(_.isDefined)

  protected [db] def findAction(namespace: Namespace, deviceId: DeviceId, version: Int): DBIO[Option[DeviceUpdateAssignment]] =
    deviceUpdateAssignments
      .filter(_.namespace === namespace)
      .filter(_.deviceId === deviceId)
      .filter(_.version === version)
      .result
      .headOption

  protected [db] def persistAction(
    namespace: Namespace,
    deviceId: DeviceId,
    correlationId: Option[CorrelationId],
    updateId: Option[UpdateId],
    version: Int)
  : DBIO[DeviceUpdateAssignment] = {
    val assignment = DeviceUpdateAssignment(namespace, deviceId, correlationId, updateId, version, served = false)

    (deviceUpdateAssignments += assignment)
      .map(_ => assignment)
      .handleIntegrityErrors(ConflictingTarget)
  }

  protected [db] def persistAction(
    namespace: Namespace,
    deviceId: DeviceId,
    correlationId: Option[CorrelationId],
    updateId: Option[UpdateId])(fileCacheRepository: FileCacheRepository)
  : DBIO[Int] = for {
    latestAssignmentVersion <- fetchLatestAction(namespace, deviceId).asTry.flatMap {
      case Success(x) => DBIO.successful(x)
      case Failure(NoTargetsScheduled) => DBIO.successful(0)
      case Failure(ex) => DBIO.failed(ex)
    }
    latestGeneratedRoleVersion <- fileCacheRepository.fetchLatestVersionAction(deviceId)
    newVersion = math.max(latestGeneratedRoleVersion.getOrElse(latestAssignmentVersion), latestAssignmentVersion) + 1
    _ <- persistAction(namespace, deviceId, correlationId, updateId, newVersion)
  } yield newVersion


  protected [db] def fetchLatestAction(namespace: Namespace, deviceId: DeviceId): DBIO[Int] =
    deviceUpdateAssignments
    /*
      // TODO: Filter by namespace causes deadlock exception for test
      // "Can schedule several updates for the same device at the same time" in  FileCacheSpec
      .filter(_.namespace === namespace)
     */
      .filter(_.deviceId === deviceId)
      .map(_.version)
      .forUpdate
      .max
      .result
      .failIfNone(NoTargetsScheduled)

  protected [db] def fetchAction(namespace: Namespace, deviceId: DeviceId, version: Int)
  : DBIO[DeviceUpdateAssignment] =
    deviceUpdateAssignments
      .filter(_.namespace === namespace)
      .filter(_.deviceId === deviceId)
      .filter(_.version === version)
      .result
      .failIfMany
      .failIfNone(NoTargetsScheduled)


  protected [db] def fetchAllAfterAction(namespace: Namespace, deviceId: DeviceId, fromVersion: Int)
  : DBIO[Seq[DeviceUpdateAssignment]] =
    deviceUpdateAssignments
      .filter(_.namespace === namespace)
      .filter(_.deviceId === deviceId)
      .filter(_.version > fromVersion)
      .sortBy(_.version)
      .result

  def getUpdatesFromTo(namespace: Namespace, deviceId: DeviceId, fromVersion: Int, toVersion: Int)
  : Future[Seq[DeviceUpdateAssignment]] = db.run {
    Schema.deviceUpdateAssignments
      .filter(_.namespace === namespace)
      .filter(_.deviceId === deviceId)
      .filter(_.version > fromVersion)
      .filter(_.version <= toVersion)
      .sortBy(_.version)
      .result
  }
}

