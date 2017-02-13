package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{Ecu, EcuTarget, Snapshot}
import com.advancedtelematic.director.data.DataType
import com.advancedtelematic.director.data.Role
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.http.Errors.{EntityAlreadyExists, MissingEntity}
import io.circe.Json
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

trait AdminRepositorySupport {
  def adminRepository(implicit db: Database, ec: ExecutionContext) = new AdminRepository()
}

object AdminRepository {
  val MissingSnapshot = MissingEntity(classOf[Snapshot])
}

protected class AdminRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.AdminRequest.RegisterEcu
  import com.advancedtelematic.director.data.DataType.{EcuSerial, Image}
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._
  import AdminRepository._

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
    Schema.deviceCurrentSnapshot
      .filter(_.device === device)
      .map(_.snapshot)
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
    val act = {
        (Schema.ecuTargets
           ++= targets.toSeq.map{case (ecuSerial, image) => EcuTarget(version, ecuSerial, image)})
        .andThen(Schema.snapshots += Snapshot(version, device))
    }.map(_ => ())

    act.transactionally
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

}

trait FileCacheRepositorySupport {
  def fileCacheRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRepository()
}

object FileCacheRepository {
  val MissingTarget = MissingEntity(classOf[EcuTarget])
  val MissingSnapshot = MissingEntity(classOf[Snapshot])

  val ConflictingTarget = EntityAlreadyExists(classOf[EcuTarget])
  val ConflictingSnapshot = EntityAlreadyExists(classOf[Snapshot])
}

protected class FileCacheRepository()(implicit db: Database, ec: ExecutionContext) {
  import org.genivi.sota.db.SlickExtensions._
  import FileCacheRepository._
  import SlickCirceMapper._
  import DataType.FileCache

  private def fetchRole(role: Role.Role, err: => Throwable)(device: Uuid, version: Int): Future[Json] = db.run {
    Schema.fileCache
      .filter(_.role === role)
      .filter(_.version === version)
      .filter(_.device === device)
      .map(_.fileEntity)
      .result
      .failIfNotSingle(err)
  }

  def fetchTarget(device: Uuid, version: Int): Future[Json] = fetchRole(Role.TARGETS, MissingTarget)(device, version)

  def fetchSnapshot(device: Uuid, version: Int): Future[Json] = fetchRole(Role.SNAPSHOT, MissingSnapshot)(device, version)

  private def storeRole(role: Role.Role, err: => Throwable)(device: Uuid, version: Int, file: Json): Future[Unit] = db.run {
    (Schema.fileCache += FileCache(role, version, device, file))
      .handleIntegrityErrors(err)
      .map(_ => ())
  }

  def storeTargets(device: Uuid, version: Int, file: Json): Future[Unit] = storeRole(Role.TARGETS, ConflictingTarget)(device, version, file)
  def storeSnapshot(device: Uuid, version: Int, file: Json): Future[Unit] = storeRole(Role.SNAPSHOT, ConflictingSnapshot)(device, version, file)
}


trait FileCacheRequestRepositorySupport {
  def fileCacheRequestRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRequestRepository()
}

object FileCacheRequestRepository {
  import DataType.FileCacheRequest

  val ConflictingFileCacheRequest = EntityAlreadyExists(classOf[FileCacheRequest])
  val MissingFileCacheRequest = MissingEntity(classOf[FileCacheRequest])
}

protected class FileCacheRequestRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.FileCacheRequestStatus._
  import org.genivi.sota.db.SlickExtensions._
  import DataType.FileCacheRequest
  import FileCacheRequestRepository._

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

object RepoNameRepository {
  val MissingNamespaceRepo = MissingEntity(classOf[Namespace])
}

protected class RepoNameRepository()(implicit db: Database, ec: ExecutionContext) {
  import org.genivi.sota.db.SlickExtensions._
  import RepoNameRepository._

  def getRepo(ns: Namespace): Future[Uuid] = db.run {
    Schema.repoNameMapping
      .filter(_.ns === ns)
      .map(_.repo)
      .result
      .failIfNotSingle(MissingNamespaceRepo)
  }
}
