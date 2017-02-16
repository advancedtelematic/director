package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{Ecu, EcuTarget, Snapshot}
import com.advancedtelematic.director.data.DataType
import com.advancedtelematic.libtuf.data.TufDataType.RoleType
import org.genivi.sota.data.{Namespace, Uuid}
import io.circe.Json
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

import Errors._

trait AdminRepositorySupport {
  def adminRepository(implicit db: Database, ec: ExecutionContext) = new AdminRepository()
}

protected class AdminRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.AdminRequest.RegisterEcu
  import com.advancedtelematic.director.data.DataType.{EcuSerial, Image}
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._

  private def byDevice(namespace: Namespace, device: Uuid): Query[Schema.EcuTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  def findImages(namespace: Namespace, device: Uuid): Future[Seq[(EcuSerial, Image)]] = db.run {
    byDevice(namespace, device)
      .map(_.ecuSerial)
      .join(Schema.currentImage).on(_ === _.id)
      .map(_._2)
      .result
      .map(_.map{ case cim => (cim.ecuSerial, cim.image)})
  }

  def createDevice(namespace: Namespace, device: Uuid, primEcu: EcuSerial, ecus: Seq[RegisterEcu]): Future[Unit] = {
    val toClean = byDevice(namespace, device)
    val clean = Schema.currentImage.filter(_.id in toClean.map(_.ecuSerial)).delete.andThen(toClean.delete)

    def register(reg: RegisterEcu) = Schema.ecu += Ecu(reg.ecu_serial, device, namespace, reg.ecu_serial == primEcu, reg.crypto)

    val act = clean.andThen(DBIO.seq(ecus.map(register) :_*))

    db.run(act.transactionally)
  }

  def getLatestVersion(namespace: Namespace, device: Uuid): DBIO[Int] =
    Schema.snapshots
      .filter(_.device === device)
      .map(_.target_version)
      .result
      .failIfNotSingle(MissingSnapshot)

  def fetchTargetVersion(namespace: Namespace, device: Uuid, version: Int): DBIO[Map[EcuSerial, Image]] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .join(Schema.ecuTargets.filter(_.version === version)).on(_.ecuSerial === _.id)
      .map(_._2)
      .result
      .map(_.groupBy(_.ecuIdentifier).mapValues(_.head.image))

  def fetchLatestTarget(namespace: Namespace, device: Uuid): Future[Map[EcuSerial, Image]] = {
    val act = getLatestVersion(namespace, device).flatMap { version =>
      fetchTargetVersion(namespace, device, version)
    }
    db.run(act.transactionally)
  }

  def storeTargetVersion(namespace: Namespace, device: Uuid, version: Int, targets: Map[EcuSerial, Image]): DBIO[Unit] = {
    val act = (Schema.ecuTargets
           ++= targets.toSeq.map{case (ecuSerial, image) => EcuTarget(version, ecuSerial, image)})

    val updateSnapshot = Schema.snapshots
      .filter(_.device === device)
      .map(_.target_version)
      .update(version)

    act.andThen(updateSnapshot).map(_ => ()).transactionally
  }

  def updateTarget(namespace: Namespace, device: Uuid, targets: Map[EcuSerial, Image]): Future[Int] = {
    val dbAct = for {
      version <- getLatestVersion(namespace, device)
      previousMap <- fetchTargetVersion(namespace, device, version)
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
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._
  import DataType.CurrentImage

  private def byDevice(namespace: Namespace, device: Uuid): Query[Schema.EcuTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  private def persistEcu(ecuManifest: EcuManifest): DBIO[Unit] = {
    Schema.currentImage.insertOrUpdate(CurrentImage(ecuManifest.ecu_serial, ecuManifest.installed_image, ecuManifest.attacks_detected)).map(_ => ())
  }

  def persistAll(ecuManifests: Seq[EcuManifest]): Future[Unit] = {
    db.run(DBIO.seq(ecuManifests.map(persistEcu(_)) :_*).transactionally)
  }

  def findEcus(namespace: Namespace, device: Uuid): Future[Seq[Ecu]] =
    db.run(byDevice(namespace, device).result)

  def getNextVersion(device: Uuid): Future[Int] = {
    val act = db.run {
      Schema.snapshots
        .filter(_.device === device)
        .result
        .failIfNotSingle(MissingSnapshot)
    }

    act.map { snapshot =>
      scala.math.min(snapshot.device_version + 1, snapshot.target_version)
    }
  }

  def updateDeviceVersion(device: Uuid, device_version: Int): Future[Unit] = db.run {
    Schema.snapshots
      .insertOrUpdateWithKey(Snapshot(device, device_version, 0),
                             _.filter(_.device === device),
                             _.copy(device_version = device_version))
      .map(_ => ())
  }
}

trait FileCacheRepositorySupport {
  def fileCacheRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRepository()
}

protected class FileCacheRepository()(implicit db: Database, ec: ExecutionContext) {
  import org.genivi.sota.db.SlickExtensions._
  import SlickCirceMapper._
  import DataType.FileCache

  private def fetchRoleType(role: RoleType.RoleType, err: => Throwable)(device: Uuid, version: Int): Future[Json] = db.run {
    Schema.fileCache
      .filter(_.role === role)
      .filter(_.version === version)
      .filter(_.device === device)
      .map(_.fileEntity)
      .result
      .failIfNotSingle(err)
  }

  def fetchTarget(device: Uuid, version: Int): Future[Json] = fetchRoleType(RoleType.TARGETS, MissingTarget)(device, version)

  def fetchSnapshot(device: Uuid, version: Int): Future[Json] = fetchRoleType(RoleType.SNAPSHOT, MissingSnapshot)(device, version)

  private def storeRoleType(role: RoleType.RoleType, err: => Throwable)(device: Uuid, version: Int, file: Json): Future[Unit] = db.run {
    (Schema.fileCache += FileCache(role, version, device, file))
      .handleIntegrityErrors(err)
      .map(_ => ())
  }

  def storeTargets(device: Uuid, version: Int, file: Json): Future[Unit] = storeRoleType(RoleType.TARGETS, ConflictingTarget)(device, version, file)
  def storeSnapshot(device: Uuid, version: Int, file: Json): Future[Unit] = storeRoleType(RoleType.SNAPSHOT, ConflictingSnapshot)(device, version, file)
}


trait FileCacheRequestRepositorySupport {
  def fileCacheRequestRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRequestRepository()
}

protected class FileCacheRequestRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.FileCacheRequestStatus._
  import org.genivi.sota.db.SlickExtensions._
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
  import org.genivi.sota.db.SlickExtensions._

  def getRepo(ns: Namespace): Future[Uuid] = db.run {
    Schema.repoNameMapping
      .filter(_.ns === ns)
      .map(_.repo)
      .result
      .failIfNotSingle(MissingNamespaceRepo)
  }
}
