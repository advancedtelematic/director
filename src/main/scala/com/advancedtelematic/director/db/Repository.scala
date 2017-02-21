package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{DeviceId, Ecu, EcuTarget, Namespace}
import com.advancedtelematic.director.data.DataType
import com.advancedtelematic.libtuf.data.TufDataType.{RoleType, RepoId}
import io.circe.Json
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._
import scala.util.{Success, Failure}

import Errors._

trait AdminRepositorySupport {
  def adminRepository(implicit db: Database, ec: ExecutionContext) = new AdminRepository()
}

protected class AdminRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.AdminRequest.RegisterEcu
  import com.advancedtelematic.director.data.DataType.{DeviceTargets, EcuSerial, Image}
  import com.advancedtelematic.libats.db.SlickExtensions._
  import com.advancedtelematic.libats.db.SlickAnyVal._
  import com.advancedtelematic.libats.codecs.SlickRefined._

  private def byDevice(namespace: Namespace, device: DeviceId): Query[Schema.EcusTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  def findImages(namespace: Namespace, device: DeviceId): Future[Seq[(EcuSerial, Image)]] = db.run {
    byDevice(namespace, device)
      .map(_.ecuSerial)
      .join(Schema.currentImage).on(_ === _.id)
      .map(_._2)
      .result
      .map(_.map(cim => cim.ecuSerial -> cim.image))
  }

  def createDevice(namespace: Namespace, device: DeviceId, primEcu: EcuSerial, ecus: Seq[RegisterEcu]): Future[Unit] = {
    val toClean = byDevice(namespace, device)
    val clean = Schema.currentImage.filter(_.id in toClean.map(_.ecuSerial)).delete.andThen(toClean.delete)

    def register(reg: RegisterEcu) = Schema.ecu += Ecu(reg.ecu_serial, device, namespace, reg.ecu_serial == primEcu, reg.clientKey)

    val act = clean.andThen(DBIO.sequence(ecus.map(register)))

    db.run(act.map(_ => ()).transactionally)
  }

  def getLatestVersion(namespace: Namespace, device: DeviceId): DBIO[Int] =
    Schema.deviceTargets
      .filter(_.device === device)
      .map(_.latestScheduledTarget)
      .result
      .failIfNotSingle(NoTargetsScheduled)

  def fetchTargetVersionAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Map[EcuSerial, Image]] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .join(Schema.ecuTargets.filter(_.version === version)).on(_.ecuSerial === _.id)
      .map(_._2)
      .result
      .map(_.groupBy(_.ecuIdentifier).mapValues(_.head.image))

  def fetchTargetVersion(namespace: Namespace, device: DeviceId, version: Int): Future[Map[EcuSerial, Image]] =
    db.run(fetchTargetVersionAction(namespace, device, version))

  def storeTargetVersion(namespace: Namespace, device: DeviceId, version: Int, targets: Map[EcuSerial, Image]): DBIO[Unit] = {
    val act = (Schema.ecuTargets
      ++= targets.map{ case (ecuSerial, image) => EcuTarget(version, ecuSerial, image)})

    val updateDeviceTargets = Schema.deviceTargets.insertOrUpdate(DeviceTargets(device, version))

    act.andThen(updateDeviceTargets).map(_ => ()).transactionally
  }

  def updateTarget(namespace: Namespace, device: DeviceId, targets: Map[EcuSerial, Image]): Future[Int] = {
    val dbAct = for {
      version <- getLatestVersion(namespace, device).asTry.flatMap {
        case Success(x) => DBIO.successful(x)
        case Failure(NoTargetsScheduled) => DBIO.successful(0)
        case Failure(ex) => DBIO.failed(ex)
      }
      previousMap <- fetchTargetVersionAction(namespace, device, version)
      new_version = version + 1
      new_targets = previousMap ++ targets
      _ <- storeTargetVersion(namespace, device, new_version, new_targets)
    } yield new_version

    db.run(dbAct.transactionally)
  }
}

trait DeviceRepositorySupport {
  def deviceRepository(implicit db: Database, ec: ExecutionContext) = new DeviceRepository()
}

protected class DeviceRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
  import com.advancedtelematic.libats.db.SlickExtensions._
  import com.advancedtelematic.libats.db.SlickAnyVal._
  import com.advancedtelematic.libats.codecs.SlickRefined._
  import DataType.{CurrentImage, DeviceCurrentTarget}

  private def byDevice(namespace: Namespace, device: DeviceId): Query[Schema.EcusTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  private def persistEcu(ecuManifest: EcuManifest): DBIO[Unit] = {
    Schema.currentImage.insertOrUpdate(CurrentImage(ecuManifest.ecu_serial, ecuManifest.installed_image, ecuManifest.attacks_detected)).map(_ => ())
  }

  def persistAllAction (ecuManifests: Seq[EcuManifest]): DBIO[Unit] =
    DBIO.sequence(ecuManifests.map(persistEcu)).map(_ => ()).transactionally

  def persistAll(ecuManifests: Seq[EcuManifest]): Future[Unit] =
    db.run(persistAllAction(ecuManifests))

  def findEcus(namespace: Namespace, device: DeviceId): Future[Seq[Ecu]] =
    db.run(byDevice(namespace, device).result)

  private def getCurrentVersionAction(device: DeviceId): DBIO[Int] =
    Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .result
      .failIfNotSingle(MissingCurrentTarget)

  def getNextVersionAction(device: DeviceId): DBIO[Int] = {
    val devVer = getCurrentVersionAction(device)

    val targetVer = Schema.deviceTargets
      .filter(_.device === device)
      .map(_.latestScheduledTarget)
      .result
      .failIfNotSingle(NoTargetsScheduled)

    devVer.zip(targetVer).map { case (device_version, target_version) =>
      scala.math.min(device_version + 1, target_version)
    }
  }

  def getNextVersion(device: DeviceId): Future[Int] = db.run(getNextVersionAction(device))

  def updateDeviceVersionAction(device: DeviceId, device_version: Int): DBIO[Unit] = {
    Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, device_version))
      .map(_ => ())
  }

}

trait FileCacheRepositorySupport {
  def fileCacheRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRepository()
}

protected class FileCacheRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.libats.db.SlickExtensions._
  import SlickCirceMapper._
  import DataType.FileCache

  private def fetchRoleType(role: RoleType.RoleType, err: => Throwable)(device: DeviceId, version: Int): Future[Json] = db.run {
    Schema.fileCache
      .filter(_.role === role)
      .filter(_.version === version)
      .filter(_.device === device)
      .map(_.fileEntity)
      .result
      .failIfNotSingle(err)
  }

  def fetchTarget(device: DeviceId, version: Int): Future[Json] = fetchRoleType(RoleType.TARGETS, MissingTarget)(device, version)

  def fetchSnapshot(device: DeviceId, version: Int): Future[Json] = fetchRoleType(RoleType.SNAPSHOT, MissingSnapshot)(device, version)

  def storeRoleTypeAction(role: RoleType.RoleType, err: => Throwable)(device: DeviceId, version: Int, file: Json): DBIO[Unit] =
    (Schema.fileCache += FileCache(role, version, device, file))
      .handleIntegrityErrors(err)
      .map(_ => ())

  def storeTargetsAction(device: DeviceId, version: Int, file: Json): DBIO[Unit] =
    storeRoleTypeAction(RoleType.TARGETS, ConflictingTarget)(device, version, file)

  def storeSnapshotAction(device: DeviceId, version: Int, file: Json): DBIO[Unit] =
    storeRoleTypeAction(RoleType.SNAPSHOT, ConflictingSnapshot)(device, version, file)
}


trait FileCacheRequestRepositorySupport {
  def fileCacheRequestRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRequestRepository()
}

protected class FileCacheRequestRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.FileCacheRequestStatus._
  import com.advancedtelematic.libats.db.SlickExtensions._
  import DataType.FileCacheRequest

  def persist(req: FileCacheRequest): Future[Unit] = db.run {
    (Schema.fileCacheRequest += req)
      .map(_ => ())
      .handleIntegrityErrors(ConflictingFileCacheRequest)
  }

  def findPending(limit: Int = 10): Future[Seq[FileCacheRequest]] = db.run {
    Schema.fileCacheRequest.filter(_.status === PENDING).take(limit).result
  }

  def updateRequest(req: FileCacheRequest): Future[Unit] = db.run {
    Schema.fileCacheRequest.update(req)
      .handleSingleUpdateError(MissingFileCacheRequest)
      .map(_ => ())
  }
}

trait RepoNameRepositorySupport {
  def repoNameRepository(implicit db: Database, ec: ExecutionContext) = new RepoNameRepository()
}

protected class RepoNameRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.libats.db.SlickAnyVal._
  import com.advancedtelematic.libats.db.SlickExtensions._

  def getRepo(ns: Namespace): Future[RepoId] = db.run {
    Schema.repoNameMapping
      .filter(_.ns === ns)
      .map(_.repo)
      .result
      .failIfNotSingle(MissingNamespaceRepo)
  }

  def storeRepo(ns: Namespace, repoId: RepoId): Future[Unit] = db.run {
    (Schema.repoNameMapping += ((ns, repoId)))
      .handleIntegrityErrors(ConflictNamespaceRepo)
      .map(_ => ())
  }
}
