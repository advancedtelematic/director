package com.advancedtelematic.director.db

import java.time.Instant

import akka.http.scaladsl.util.FastFuture
import cats.Show
import com.advancedtelematic.director.data.DbDataType.{Assignment, DbSignedRole, Device, Ecu, EcuTarget, EcuTargetId, HardwareUpdate, SHA256Checksum}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{EcuIdentifier, ErrorCode, PaginationResult}
import com.advancedtelematic.libats.http.Errors.{EntityAlreadyExists, MissingEntity, MissingEntityId, RawError}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, RoleType, TargetFilename}
import com.advancedtelematic.libtuf_server.data.TufSlickMappings._
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import slick.jdbc.MySQLProfile.api._
import SlickMapping._
import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.http.Errors
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric._
import com.advancedtelematic.libtuf.data.ClientDataType.TufRole
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType

import scala.concurrent.{ExecutionContext, Future}

protected trait DatabaseSupport {
  implicit val ec: ExecutionContext
  implicit val db: Database
}

trait DeviceRepositorySupport extends DatabaseSupport {
  lazy val deviceRepository = new DeviceRepository()
}

protected class DeviceRepository()(implicit val db: Database, val ec: ExecutionContext) {
  def create(ns: Namespace, deviceId: DeviceId, primaryEcuId: EcuIdentifier, ecus: Seq[Ecu]): Future[Unit] = {
    val io = for {
      _ <- Schema.ecus ++= ecus
      _ <- Schema.devices += Device(ns, deviceId, primaryEcuId)
    } yield ()

    db.run(io.transactionally)
  }
}

trait RepoNamespaceRepositorySupport extends DatabaseSupport {
  lazy val repoNamespaceRepo = new RepoNamespaceRepository()
}

protected[db] class RepoNamespaceRepository()(implicit val db: Database, val ec: ExecutionContext) {
  import Schema.repoNamespaces

  val MissingRepoNamespace = MissingEntity[(RepoId, Namespace)]()
  val AlreadyExists = EntityAlreadyExists[(RepoId, Namespace)]()

  def persist(repoId: RepoId, namespace: Namespace): Future[Unit] = db.run {
    (repoNamespaces += (repoId -> namespace)).map(_ => ()).handleIntegrityErrors(AlreadyExists)
  }

  def ensureNotExists(namespace: Namespace): Future[Unit] =
    findFor(namespace)
      .flatMap(_ => FastFuture.failed(AlreadyExists))
      .recover { case MissingRepoNamespace => () }

  def findFor(namespace: Namespace): Future[RepoId] = db.run {
    repoNamespaces
      .filter(_.namespace === namespace)
      .map(_.repoId)
      .result
      .headOption
      .failIfNone(MissingRepoNamespace)
  }

  def belongsTo(repoId: RepoId, namespace: Namespace): Future[Boolean] = db.run {
    repoNamespaces
      .filter(_.repoId === repoId)
      .filter(_.namespace === namespace)
      .size
      .result
      .map(_ > 0)
  }
}

object HardwareUpdateRepository {
  implicit val showHardwareUpdateId = Show.show[(Namespace, UpdateId)] { case (ns, id) =>
    s"($ns, $id)"
  }

  def MissingHardwareUpdate(namespace: Namespace, id: UpdateId) = MissingEntityId[(Namespace, UpdateId)](namespace -> id)
}

trait HardwareUpdateRepositorySupport extends DatabaseSupport {
  lazy val hardwareUpdateRepository = new HardwareUpdateRepository()
}

protected class HardwareUpdateRepository()(implicit val db: Database, val ec: ExecutionContext) {
  import HardwareUpdateRepository._

  protected [db] def persistAction(hardwareUpdate: HardwareUpdate): DBIO[Unit] = {
    (Schema.hardwareUpdates += hardwareUpdate).map(_ => ())
  }

  def findBy(ns: Namespace, id: UpdateId): Future[Map[HardwareIdentifier, HardwareUpdate]] = db.run {
    Schema.hardwareUpdates
      .filter(_.namespace === ns).filter(_.id === id)
      .result
      .failIfEmpty(MissingHardwareUpdate(ns, id))
      .map { hwUpdates =>
        hwUpdates.map(hwUpdate => hwUpdate.hardwareId -> hwUpdate).toMap
      }
  }

  def findUpdateTargets(ns: Namespace, id: UpdateId): Future[Seq[(HardwareUpdate, Option[EcuTarget], EcuTarget)]] = db.run {
    val io = Schema.hardwareUpdates
      .filter(_.namespace === ns).filter(_.id === id)
      .join(Schema.ecuTargets).on { case (hwU, toTarget) => hwU.toTarget === toTarget.id }
      .joinLeft(Schema.ecuTargets).on { case ((hw, _), fromTarget) => hw.fromTarget === fromTarget.id }
      .map { case ((hw, toTarget), fromTarget) =>
        (hw, fromTarget, toTarget)
      }

    io.result.failIfEmpty(MissingHardwareUpdate(ns, id))
  }
}

trait EcuTargetsRepositorySupport extends DatabaseSupport {
  lazy val ecuTargetsRepository = new EcuTargetsRepository()
}

protected class EcuTargetsRepository()(implicit val db: Database, val ec: ExecutionContext) {
  protected [db] def persistAction(ecuTarget: EcuTarget): DBIO[EcuTargetId] = {
    (Schema.ecuTargets += ecuTarget).map(_ => ecuTarget.id)
  }

  def find(ns: Namespace, id: EcuTargetId): Future[EcuTarget] = db.run {
    Schema.ecuTargets
      .filter(_.namespace === ns)
      .filter(_.id === id).result.failIfNotSingle(MissingEntityId[EcuTargetId](id))
  }
}

trait AssignmentsRepositorySupport extends DatabaseSupport {
  lazy val assignmentsRepository = new AssignmentsRepository()
}

protected class AssignmentsRepository()(implicit val db: Database, val ec: ExecutionContext) {

  def persistMany(assignments: Seq[Assignment]): Future[Unit] = db.run {
    (Schema.assignments ++= assignments).map(_ => ())
  }

  def findBy(deviceId: DeviceId): Future[Seq[Assignment]] = db.run {
    Schema.assignments.filter(_.deviceId === deviceId).result
  }

  def existsFor(ecus: Set[EcuIdentifier]): Future[Boolean] = db.run {
    Schema.assignments.filter(_.ecuId.inSet(ecus)).exists.result
  }

  def findLastCreated(deviceId: DeviceId): Future[Option[Instant]] = db.run {
    Schema.assignments.filter(_.deviceId === deviceId).sortBy(_.createdAt.reverse).map(_.createdAt).result.headOption
  }

  def setAllInFlight(deviceId: DeviceId): Future[Unit] = db.run {
    Schema.assignments.filter(_.deviceId === deviceId).map(_.inFlight).update(true).map(_ => ())
  }
}


trait EcuRepositorySupport extends DatabaseSupport {
  lazy val ecuRepository = new EcuRepository()
}

protected class EcuRepository()(implicit val db: Database, val ec: ExecutionContext) {
  def findBy(deviceId: DeviceId): Future[Seq[Ecu]] = db.run {
    Schema.ecus.filter(_.deviceId === deviceId).result
  }

  def findBySerial(ns: Namespace, deviceId: DeviceId, ecuSerial: EcuIdentifier): Future[Ecu] = db.run {
    Schema.ecus.filter(_.deviceId === deviceId).filter(_.namespace === ns).filter(_.ecuSerial === ecuSerial).result.failIfNotSingle(MissingEntity[Ecu]())
  }

  def findTargets(ns: Namespace, deviceId: DeviceId): Future[Seq[(Ecu, EcuTarget)]] = db.run {
    Schema.ecus.filter(_.deviceId === deviceId).join(Schema.ecuTargets).on(_.installedTarget === _.id).result
  }

  def countEcusWithImages(targets: Set[TargetFilename]): Future[Map[TargetFilename, Int]] = db.run {
    Schema.ecus.join(Schema.ecuTargets.filter(_.filename.inSet(targets))).on(_.installedTarget === _.id)
      .map { case (ecu, ecuTarget) => ecu.ecuSerial -> ecuTarget.filename }
      .result
      .map { targetByEcu =>
        targetByEcu
          .groupBy { case (_, filename) => filename }
          .mapValues(_.size)
      }
  }

  def findAllHardwareIdentifiers(ns: Namespace, offset: Long, limit: Long): Future[PaginationResult[HardwareIdentifier]] = db.run {
    Schema.ecus
      .filter(_.namespace === ns)
      .map(_.hardwareId)
      .distinct
      .paginateAndSortResult(identity, offset = offset, limit = limit)
  }

  def findFor(deviceId: DeviceId): Future[Map[EcuIdentifier, Ecu]] = db.run {
    Schema.ecus.filter(_.deviceId === deviceId).result
  }.map(_.map(e => e.ecuSerial -> e).toMap)

  def findFor(devices: Set[DeviceId], hardwareIds: Set[HardwareIdentifier]): Future[Seq[Ecu]] = db.run {
    Schema.ecus.filter(_.deviceId.inSet(devices)).filter(_.hardwareId.inSet(hardwareIds)).result
  }

  def findDevicePrimary(ns: Namespace, deviceId: DeviceId): Future[Ecu] = db.run {
    Schema.devices.filter(_.id === deviceId).filter(_.namespace === ns)
      .join(Schema.ecus).on { case (d, e) => d.primaryEcu === e.ecuSerial }
      .map(_._2).resultHead(Errors.DeviceMissingPrimaryEcu)
  }
}

trait DbSignedRoleRepositorySupport extends DatabaseSupport {
  lazy val dbSignedRoleRepository = new DbSignedRoleRepository()
}


protected[db] class DbSignedRoleRepository()(implicit val db: Database, val ec: ExecutionContext) {
  import Schema.signedRoles

  def persist(signedRole: DbSignedRole, forceVersion: Boolean = false): Future[DbSignedRole] =
    db.run(persistAction(signedRole, forceVersion).transactionally)

  protected [db] def persistAction(signedRole: DbSignedRole, forceVersion: Boolean): DBIO[DbSignedRole] = {
    signedRoles
      .filter(_.device === signedRole.device)
      .filter(_.role === signedRole.role)
      .sortBy(_.version.reverse)
      .result
      .headOption
      .flatMap { old =>
        if(!forceVersion)
          ensureVersionBumpIsValid(signedRole)(old)
        else
          DBIO.successful(())
      }
      .flatMap(_ => signedRoles += signedRole)
      .map(_ => signedRole)
  }

  def persistAll(signedRoles: List[DbSignedRole]): Future[Seq[DbSignedRole]] = db.run {
    DBIO.sequence(signedRoles.map(sr => persistAction(sr, forceVersion = false))).transactionally
  }

  def findLatest[T](deviceId: DeviceId)(implicit ev: TufRole[T]): Future[DbSignedRole] =
    db.run {
      signedRoles
        .filter(_.device === deviceId)
        .filter(_.role === ev.roleType)
        .sortBy(_.version.reverse)
        .result
        .headOption
        .failIfNone(Errors.SignedRoleNotFound[T](deviceId))
    }

  def findLastCreated[T](deviceId: DeviceId)(implicit ev: TufRole[T]): Future[Option[Instant]] = db.run {
    signedRoles.filter(_.device === deviceId).filter(_.role === ev.roleType).sortBy(_.createdAt.reverse).map(_.createdAt).result.headOption
  }

  def storeAll(deviceId: DeviceId, signedRoles: List[DbSignedRole]): Future[Unit] = db.run {
    DBIO.sequence(signedRoles.map(sr => persistAction(sr, forceVersion = false)))
      .transactionally
      .map(_ => ())
  }

  private def ensureVersionBumpIsValid(signedRole: DbSignedRole)(oldSignedRole: Option[DbSignedRole]): DBIO[Unit] =
    oldSignedRole match {
      case Some(sr) if signedRole.role != RoleType.ROOT && sr.version != signedRole.version - 1 =>
        DBIO.failed(Errors.InvalidVersionBumpError(sr.version, signedRole.version, signedRole.role))
      case _ => DBIO.successful(())
    }
}
