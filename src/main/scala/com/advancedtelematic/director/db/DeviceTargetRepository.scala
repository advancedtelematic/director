package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.DeviceUpdateTarget
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.director.db.SlickMapping._

import Errors._

trait DeviceTargetRepositorySupport {
  def deviceTargetRepository(implicit db: Database, ec: ExecutionContext) = new DeviceTargetRepository()
}

protected class DeviceTargetRepository()(implicit db: Database, ec: ExecutionContext) {
  import Schema.deviceTargets

  def exists(namespace: Namespace, device: DeviceId, version: Int): Future[Boolean] =
    db.run(existsAction(namespace, device, version))

  protected [db] def existsAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Boolean] =
    deviceTargets
      .filter(_.device === device)
      .filter(_.version === version)
      .exists
      .result

  protected [db] def persistAction(device: DeviceId, correlationId: Option[CorrelationId], updateId: Option[UpdateId], version: Int): DBIO[DeviceUpdateTarget] = {
    val target = DeviceUpdateTarget(device, correlationId, updateId, version, inFlight = false)

    (deviceTargets += target)
      .map(_ => target)
      .handleIntegrityErrors(ConflictingTarget)
  }

  protected [db] def fetchLatestAction(namespace: Namespace, deviceId: DeviceId): DBIO[Int] =
    deviceTargets
      .filter(_.device === deviceId)
      .map(_.version)
      .forUpdate
      .max
      .result
      .failIfNone(NoTargetsScheduled)

  protected [db] def fetchAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[DeviceUpdateTarget] =
    deviceTargets
      .filter(_.device === device)
      .filter(_.version === version)
      .result
      .failIfMany
      .failIfNone(NoTargetsScheduled)


  protected [db] def fetchAllAfterAction(deviceId: DeviceId, fromVersion: Int): DBIO[Seq[DeviceUpdateTarget]] =
    deviceTargets
      .filter(_.device === deviceId)
      .filter(_.version > fromVersion)
      .sortBy(_.version)
      .result

  def getUpdatesFromTo(namespace: Namespace, device: DeviceId, fromVersion: Int, toVersion: Int)
    : Future[Seq[DeviceUpdateTarget]] = db.run {
    Schema.deviceTargets
      .filter(_.device === device)
      .filter(_.version > fromVersion)
      .filter(_.version <= toVersion)
      .sortBy(_.version)
      .result
  }
}

