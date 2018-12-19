package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.QueueResponse
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceUpdateTarget, EcuUpdateTarget}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.jdbc.MySQLProfile.api._

import Errors._

trait EcuTargetRepositorySupport {
  def ecuTargetRepository(implicit db: Database, ec: ExecutionContext) = new EcuTargetRepository()
}

protected class EcuTargetRepository()(implicit val db: Database, val ec: ExecutionContext)
  extends DeviceTargetRepositorySupport {

  import Schema.ecuUpdateTarget

  private [this] def persistAction(
    namespace: Namespace, deviceId: DeviceId, version: Int, targets: Map[EcuIdentifier, CustomImage]
  ): DBIO[Unit] = {

    val act = (ecuUpdateTarget ++= targets.map {
      case (ecuSerial, customImage) => EcuUpdateTarget(namespace, deviceId, ecuSerial, version, customImage)
    })

    act.map(_ => ()).transactionally
  }

  protected [db] def persistAction(
    namespace: Namespace, deviceId: DeviceId, targets: Map[EcuIdentifier, CustomImage])
  : DBIO[Int] = for {
    version <- deviceTargetRepository.fetchLatestAction(namespace, deviceId).asTry.flatMap {
      case Success(x) => DBIO.successful(x)
      case Failure(NoTargetsScheduled) => DBIO.successful(0)
      case Failure(ex) => DBIO.failed(ex)
    }
    new_version = version + 1
    _ <- persistAction(namespace, deviceId, new_version, targets)
  } yield new_version

  protected [db] def fetchAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Map[EcuIdentifier, CustomImage]] =
    ecuUpdateTarget
      .filter(_.namespace === namespace)
      .filter(_.deviceId === device)
      .filter(_.version === version)
      .map { ecuTarget => ecuTarget.ecuId -> ecuTarget.customImage }
      .result
      .map(_.toMap)

  def fetch(namespace: Namespace, device: DeviceId, version: Int): Future[Map[EcuIdentifier, CustomImage]] =
    db.run(fetchAction(namespace, device, version))

  def fetchQueue(namespace: Namespace, device: DeviceId): Future[Seq[QueueResponse]] = db.run {
    def queueResult(updateTarget: DeviceUpdateTarget): DBIO[QueueResponse] = for {
      targets <- fetchAction(namespace, device, updateTarget.targetVersion)
    } yield QueueResponse(updateTarget.correlationId, targets, updateTarget.inFlight)

    val versionOfDevice: DBIO[Int] = Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .result
      .failIfMany
      .map(_.getOrElse(0))

    for {
      currentVersion <- versionOfDevice
      updates <- deviceTargetRepository.fetchAllAfterAction(device, currentVersion)
      queue <- DBIO.sequence(updates.map(queueResult))
    } yield queue
  }
}

