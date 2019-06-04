package com.advancedtelematic.director.db

import java.time.Instant

import com.advancedtelematic.director.data.AdminRequest.EcuInfoImage
import com.advancedtelematic.director.data.DataType.{Ecu, FileCacheRequest, MultiTargetUpdateRow}
import com.advancedtelematic.director.data.{DataType, FileCacheRequestStatus}
import com.advancedtelematic.director.db.Errors._
import com.advancedtelematic.director.db.SlickMapping._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, RoleType, TargetFilename, TufKey}
import com.advancedtelematic.libtuf_server.data.TufSlickMappings._
import io.circe.Json
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

trait AdminRepositorySupport {
  def adminRepository(implicit db: Database, ec: ExecutionContext) = new AdminRepository()
}

protected class AdminRepository()(implicit db: Database, ec: ExecutionContext) extends DeviceRepositorySupport
    with FileCacheRequestRepositorySupport {
  import com.advancedtelematic.director.data.AdminRequest.{EcuInfoResponse, RegisterEcu}
  import com.advancedtelematic.director.data.DataType.{CustomImage, Hashes, Image}

  implicit private class NotInCampaign(query: Query[Rep[DeviceId], DeviceId, Seq]) {
    def notInACampaign(namespace: Namespace): Query[Rep[DeviceId], DeviceId, Seq] = {

      def devUpdateAssignments = Schema.deviceUpdateAssignments
        .filter(_.namespace === namespace)
        .groupBy(_.deviceId)
        .map{case (devId, q) => (devId, q.map(_.version).max.getOrElse(0))}

      val reportedButNotInACampaign = query
        .join(Schema.deviceCurrentTarget).on(_ === _.device)
        .map{case (devId, devCurTarget) => (devId, devCurTarget.deviceCurrentTarget)}
        .joinLeft(devUpdateAssignments).on(_._1 === _._1)
        .map{case ((devId, curTarg), devUpdate) => (devId, curTarg, devUpdate.map(_._2).getOrElse(curTarg))}
        .filter{ case(_, cur, lastScheduled) => cur === lastScheduled}
        .map(_._1)

      val notReported = query.filterNot(dev => dev.in(Schema.deviceCurrentTarget.map(_.device)))

      reportedButNotInACampaign.union(notReported)
    }
  }

  protected [db] def devicesNotInACampaign(namespace: Namespace, devices: Seq[DeviceId]): Query[Rep[DeviceId], DeviceId, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .map(_.device)
      .filter(_.inSet(devices))
      .notInACampaign(namespace)

  private def byDevice(namespace: Namespace, device: DeviceId): Query[Schema.EcusTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  protected [db] def fetchHwMappingAction(namespace: Namespace, device: DeviceId): DBIO[Map[EcuIdentifier, (HardwareIdentifier, Option[Image])]] =
    byDevice(namespace, device)
      .joinLeft(Schema.currentImage).on((ecu, curImage) => ecu.namespace === curImage.namespace && ecu.ecuSerial === curImage.id)
      .map{case (x,y) => (x.ecuSerial, x.hardwareId, y)}
      .result
      .failIfEmpty(DeviceMissing)
      .map(_.map{case (id, hw, img) => id -> ((hw, img.map(_.image)))}.toMap)

  protected [db] def findImagesAction(namespace: Namespace, device: DeviceId): DBIO[Seq[(EcuIdentifier, Image)]] =
    byDevice(namespace, device)
      .join(Schema.currentImage).on((ecu, curImage) => ecu.namespace === curImage.namespace && ecu.ecuSerial === curImage.id)
      .map(_._2)
      .result
      .map(_.map(cim => cim.ecuSerial -> cim.image))

  def findImages(namespace: Namespace, device: DeviceId): Future[Seq[(EcuIdentifier, Image)]] = db.run {
    findImagesAction(namespace, device)
  }

  def findAffected(namespace: Namespace, filepath: TargetFilename, offset: Long, limit: Long): Future[PaginationResult[DeviceId]] = db.run {
    Schema.currentImage
      .filter(_.filepath === filepath)
      .filter(_.namespace === namespace)
      .map(_.id)
      .join(Schema.ecu.filter(_.namespace === namespace)).on(_ === _.ecuSerial)
      .map(_._2)
      .map(_.device)
      .notInACampaign(namespace)
      .distinct
      .paginateResult(offset = offset, limit = limit)
  }

  def findDevice(namespace: Namespace, device: DeviceId): Future[Seq[EcuInfoResponse]] = db.run {
    Schema.ecu
        .filter(_.namespace === namespace).filter(_.device === device)
        .join(Schema.currentImage).on(_.ecuSerial === _.id)
        .map { case (ecu, curImage) => (ecu.ecuSerial, ecu.hardwareId, ecu.primary, curImage.filepath, curImage.length, curImage.checksum) }
        .result
        .failIfEmpty(MissingDevice)
        .map { _.map { case (id, hardwareId, primary, filepath, size, checksum) =>
          EcuInfoResponse(id, hardwareId, primary, EcuInfoImage(filepath, size, Hashes(checksum.hash)))
        } }
  }

  def findDevices(namespace: Namespace, offset: Long, limit: Long): Future[PaginationResult[DeviceId]] = db.run {
    Schema.ecu
      .filter(_.namespace === namespace)
      .map(x => (x.device, x.createdAt))
      .distinctOn(_._1)
      .paginateAndSortResult(_._2, offset = offset, limit = limit)
      .map(_.map(_._1))
  }

  def findPublicKey(namespace: Namespace, device: DeviceId, ecu_serial: EcuIdentifier): Future[TufKey] = db.run {
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .filter(_.ecuSerial === ecu_serial)
      .map(_.publicKey)
      .result
      .failIfNotSingle(MissingEcu)
  }

  def createDevice(namespace: Namespace, device: DeviceId, primEcu: EcuIdentifier, ecus: Seq[RegisterEcu]): Future[Unit] = {
    val toClean = byDevice(namespace, device)
    val clean = Schema.currentImage
      .filter(_.namespace === namespace)
      .filter(_.id in toClean.map(_.ecuSerial))
      .delete.andThen(toClean.delete)

    def register(reg: RegisterEcu) =
      (Schema.ecu += Ecu(reg.ecu_serial, device, namespace, reg.ecu_serial == primEcu, reg.hardwareId, reg.clientKey))
        .handleIntegrityErrors(EcuAlreadyRegistered)

    val act = clean.andThen(DBIO.sequence(ecus.map(register)))
      .andThen(deviceRepository.createEmptyTarget(namespace, device))
    db.run(act.map(_ => ()).transactionally)
  }

  // TODO: Remove this
  def fetchCustomTargetVersion(namespace: Namespace, device: DeviceId, version: Int): Future[Map[EcuIdentifier, (HardwareIdentifier, CustomImage)]] = db.run {
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .join(Schema.ecuUpdateAssignments
        .filter(_.namespace === namespace)
        .filter(_.deviceId === device)
        .filter(_.version === version)).on(_.ecuSerial === _.ecuId)
      .map { case (ecu, ecuTarget) => ecuTarget.ecuId -> ((ecu.hardwareId, ecuTarget.customImage)) }
      .result
      .map(_.toMap)
  }

  def getPrimaryEcuForDevice(device: DeviceId): Future[EcuIdentifier] = db.run {
    Schema.ecu
      .filter(_.device === device)
      .filter(_.primary)
      .map(_.ecuSerial)
      .result
      .failIfNotSingle(DeviceMissingPrimaryEcu)
  }

  def findAllHardwareIdentifiers(namespace: Namespace, offset: Long, limit: Long): Future[PaginationResult[HardwareIdentifier]] = db.run {
    Schema.ecu
      .filter(_.namespace === namespace)
      .map(_.hardwareId)
      .distinct
      .paginateAndSortResult(identity, offset = offset, limit = limit)
  }

  def countInstalledImages(namespace: Namespace, filepaths: Seq[TargetFilename]): Future[Map[TargetFilename, Int]] = db.run {
    Schema.currentImage
      .filter(_.namespace === namespace)
      .filter(_.filepath inSet(filepaths))
      .groupBy(_.filepath)
      .map { case (filepath, results) => (filepath, results.length) }
      .drop(0) //workaround for slick issue: https://github.com/slick/slick/issues/1355
      .result
      .map(_.toMap)
  }
}

trait DeviceRepositorySupport {
  def deviceRepository(implicit db: Database, ec: ExecutionContext) = new DeviceRepository()
}

protected class DeviceRepository()(implicit db: Database, ec: ExecutionContext) extends FileCacheRequestRepositorySupport {
  import DataType.{CurrentImage, DeviceCurrentTarget, DeviceUpdateAssignment}
  import com.advancedtelematic.director.data.AdminRequest.RegisterEcu
  import com.advancedtelematic.director.data.DeviceRequest.EcuManifest

  private def byDevice(namespace: Namespace, device: DeviceId): Query[Schema.EcusTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  private def persistEcu(namespace: Namespace, ecuManifest: EcuManifest): DBIO[Unit] = {
    Schema.currentImage.insertOrUpdate(CurrentImage(namespace, ecuManifest.ecu_serial, ecuManifest.installed_image, ecuManifest.attacks_detected)).map(_ => ())
  }

  protected [db] def persistAllAction(namespace: Namespace, ecuManifests: Seq[EcuManifest]): DBIO[Unit] =
    DBIO.sequence(ecuManifests.map(persistEcu(namespace, _))).map(_ => ()).transactionally

  def persistAll(namespace: Namespace, ecuManifests: Seq[EcuManifest]): Future[Unit] =
    db.run(persistAllAction(namespace, ecuManifests))

  protected [db] def createEmptyTarget(namespace: Namespace, device: DeviceId): DBIO[Unit] = {
    val fcr = FileCacheRequest(namespace, 0, device, FileCacheRequestStatus.PENDING, 0)
    (Schema.deviceUpdateAssignments += DeviceUpdateAssignment(namespace, device, None, None, 0, served = false))
      .andThen(fileCacheRequestRepository.persistAction(fcr))
  }

  def create(namespace: Namespace, device: DeviceId, primEcu: EcuIdentifier, ecus: Seq[RegisterEcu]): Future[Unit] = {
    def register(reg: RegisterEcu) =
      (Schema.ecu += Ecu(reg.ecu_serial, device, namespace, reg.ecu_serial == primEcu, reg.hardwareId, reg.clientKey))
        .handleIntegrityErrors(EcuAlreadyRegistered)

    val dbAct = byDevice(namespace, device).exists.result.flatMap {
      case false => DBIO.sequence(ecus.map(register)).andThen(createEmptyTarget(namespace, device))
      case true  => DBIO.failed(DeviceAlreadyRegistered)
    }

    db.run(dbAct.transactionally)
  }

  def findEcus(namespace: Namespace, device: DeviceId): Future[Seq[Ecu]] =
    db.run(byDevice(namespace, device).result)

  def findEcuIdentifiers(namespace: Namespace, device: DeviceId): Future[Set[EcuIdentifier]] =
    db.run(byDevice(namespace, device).map(_.ecuSerial).to[Set].result)

  protected [db] def getCurrentVersionAction(device: DeviceId): DBIO[Option[Int]] =
    Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .result
      .failIfMany

  def getCurrentVersion(device: DeviceId): Future[Int] = db.run {
    getCurrentVersionAction(device)
      .failIfNone(MissingCurrentTarget)
  }

  def getCurrentVersionSetIfInitialAction(device: DeviceId): DBIO[Int] = {
    getCurrentVersionAction(device).flatMap {
      case None => updateDeviceVersionAction(device, 0).map { _ => 0 }
      case Some(current_version) => DBIO.successful(current_version)
    }
  }

  protected [db] def updateDeviceVersionAction(device: DeviceId, device_version: Int): DBIO[Unit] = {
    Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, device_version))
      .map(_ => ())
  }

  def setAsInFlight(namespace: Namespace, device: DeviceId, version: Int): Future[Int] = db.run {
    val stillValid = Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .forUpdate
      .result
      .failIfMany
      .map(_.getOrElse(0))
      .flatMap{ currentVersion =>
      if (currentVersion <= version) {
        DBIO.successful(())
      } else {
        DBIO.failed(FetchingCancelledUpdate)
      }
    }

    val act = Schema.deviceUpdateAssignments
      .filter(_.namespace === namespace)
      .filter(_.deviceId === device)
      .filter(_.version === version)
      .map(_.served)
      .update(true)

    stillValid.andThen(act).transactionally
  }
}

trait FileCacheRepositorySupport {
  def fileCacheRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRepository()
}

protected class FileCacheRepository()(implicit db: Database, ec: ExecutionContext) {
  import DataType.FileCache
  import com.advancedtelematic.libats.slick.db.SlickCirceMapper.jsonMapper
  import com.advancedtelematic.libtuf.data.ClientCodecs._
  import com.advancedtelematic.libtuf.data.ClientDataType.{SnapshotRole, TargetsRole, TimestampRole}
  import com.advancedtelematic.libtuf.data.TufCodecs._
  import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
  import com.advancedtelematic.libtuf_server.data.TufSlickMappings.roleTypeMapper
  import io.circe.syntax._

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

  def fetchTimestamp(device: DeviceId, version: Int): Future[Json] = fetchRoleType(RoleType.TIMESTAMP, MissingTimestamp)(device, version)

  protected [db] def storeRoleTypeAction(role: RoleType.RoleType, err: => Throwable)(device: DeviceId, version: Int, expires: Instant, file: Json): DBIO[Unit] =
    Schema.fileCache.insertOrUpdate(FileCache(role, version, device, expires, file))
      .handleIntegrityErrors(err)
      .map(_ => ())

  def storeJson(device: DeviceId, version: Int, expires: Instant, targets: SignedPayload[TargetsRole],
                snapshots: SignedPayload[SnapshotRole], timestamp: SignedPayload[TimestampRole]): Future[Unit] = db.run {
    storeRoleTypeAction(RoleType.TARGETS, ConflictingTarget)(device, version, expires, targets.asJson)
      .andThen(storeRoleTypeAction(RoleType.SNAPSHOT, ConflictingSnapshot)(device, version, expires, snapshots.asJson))
      .andThen(storeRoleTypeAction(RoleType.TIMESTAMP, ConflictingTimestamp)(device, version, expires, timestamp.asJson))
      .transactionally
  }

  def haveExpired(device: DeviceId, version: Int): Future[Boolean] = db.run {
    Schema.fileCache
      .filter(_.device === device)
      .filter(_.version === version)
      .filter(_.role === RoleType.TIMESTAMP)
      .map(_.expires)
      .result
      .failIfNotSingle(NoCacheEntry)
  }.map (_.isBefore(Instant.now()))

  def versionIsCached(device: DeviceId, version: Int): Future[Boolean] = db.run {
    Schema.fileCache
      .filter(_.device === device)
      .filter(_.version === version)
      .filter(_.role === RoleType.TIMESTAMP)
      .exists
      .result
  }
}


trait FileCacheRequestRepositorySupport {
  def fileCacheRequestRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRequestRepository()
}

protected class FileCacheRequestRepository()(implicit db: Database, ec: ExecutionContext) {

  protected [db] def persistAction(req: FileCacheRequest): DBIO[Unit] =
    (Schema.fileCacheRequest += req)
      .map(_ => ())
      .handleIntegrityErrors(ConflictingFileCacheRequest)

  def persist(req: FileCacheRequest): Future[Unit] = db.run {
    persistAction(req)
  }

  def findPending(limit: Int = 10): Future[Seq[FileCacheRequest]] = db.run {
    Schema.fileCacheRequest.filter(_.status === FileCacheRequestStatus.PENDING).take(limit).result
  }

  def updateRequest(req: FileCacheRequest): Future[Unit] = db.run {
    Schema.fileCacheRequest
      .filter(_.namespace === req.namespace)
      .filter(_.timestampVersion === req.timestampVersion)
      .filter(_.device === req.device)
      .map(_.status)
      .update(req.status)
      .handleSingleUpdateError(MissingFileCacheRequest)
      .map(_ => ())
  }

  def findByVersion(namespace: Namespace, device: DeviceId, version: Int): Future[FileCacheRequest] = db.run {
    Schema.fileCacheRequest
      .filter(_.namespace === namespace)
      .filter(_.timestampVersion === version)
      .filter(_.device === device)
      .result
      .failIfNotSingle(MissingFileCacheRequest)
  }
}

trait RepoNameRepositorySupport {
  def repoNameRepository(implicit db: Database, ec: ExecutionContext) = new RepoNameRepository()
}

protected class RepoNameRepository()(implicit db: Database, ec: ExecutionContext) {
  import DataType.RepoName
  import akka.NotUsed
  import akka.stream.scaladsl.Source

  def getRepo(ns: Namespace): Future[RepoId] = db.run {
    Schema.repoNames
      .filter(_.ns === ns)
      .map(_.repo)
      .result
      .failIfNotSingle(MissingNamespaceRepo)
  }

  protected [db] def persistAction(ns: Namespace, repoId: RepoId): DBIO[RepoName] = {
    val repoName = RepoName(ns, repoId)
    (Schema.repoNames += repoName)
      .handleIntegrityErrors(ConflictNamespaceRepo)
      .map(_ => repoName)
  }

  def persist(ns: Namespace, repoId: RepoId): Future[RepoName] = db.run(persistAction(ns, repoId))

  def streamNamespaces: Source[Namespace, NotUsed] = Source.fromPublisher(db.stream(Schema.repoNames.map(_.ns).result))
}

trait MultiTargetUpdatesRepositorySupport {
  def multiTargetUpdatesRepository(implicit db: Database, ec: ExecutionContext) = new MultiTargetUpdatesRepository()
}

protected class MultiTargetUpdatesRepository()(implicit db: Database, ec: ExecutionContext) {

  protected [db] def fetchAction(id: UpdateId, ns: Namespace): DBIO[Seq[MultiTargetUpdateRow]] =
    Schema.multiTargets
      .filter(_.id === id)
      .filter(_.namespace === ns)
      .result
      .failIfEmpty(MissingMultiTargetUpdate)

  def fetch(id: UpdateId, ns: Namespace): Future[Seq[MultiTargetUpdateRow]] = db.run {
    fetchAction(id, ns)
  }

  def requireDiff(ns: Namespace, id: UpdateId): Future[Boolean] = db.run {
    Schema.multiTargets
      .filter(_.namespace === ns)
      .filter(_.id === id)
      .map(_.generateDiff)
      .result
      .failIfEmpty(MissingMultiTargetUpdate)
      .map(_.contains(true))
  }

  def create(rows: Seq[MultiTargetUpdateRow]): Future[Unit] = db.run {
    (Schema.multiTargets ++= rows)
      .handleIntegrityErrors(ConflictingMultiTargetUpdate)
      .map(_ => ())
  }
}

trait AutoUpdateRepositorySupport {
  def autoUpdateRepository(implicit db: Database, ec: ExecutionContext) = new AutoUpdateRepository()
}

protected class AutoUpdateRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.DataType.{AutoUpdate, TargetUpdate}
  import com.advancedtelematic.libtuf.data.TufDataType.TargetName

  def persist(namespace: Namespace, device: DeviceId, ecuId: EcuIdentifier, targetName: TargetName): Future[AutoUpdate] = db.run {
    val autoUpdate = AutoUpdate(namespace, device, ecuId, targetName)
    (Schema.autoUpdates += autoUpdate)
      .handleIntegrityErrors(ConflictingAutoUpdate)
      .map(_ => autoUpdate)
  }

  def remove(namespace: Namespace, device: DeviceId, ecuId: EcuIdentifier, targetName: TargetName): Future[Boolean] = db.run {
    Schema
      .autoUpdates
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .filter(_.ecuSerial === ecuId)
      .filter(_.targetName === targetName)
      .delete
      .map(_ > 0)
  }

  def removeAll(namespace: Namespace, device: DeviceId, ecuSerial: EcuIdentifier): Future[Unit] = db.run {
    Schema
      .autoUpdates
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .filter(_.ecuSerial === ecuSerial)
      .delete
      .map(_ => ())
  }

  def findOnDevice(namespace: Namespace, device: DeviceId, ecuSerial: EcuIdentifier): Future[Seq[TargetName]] = db.run {
    Schema
      .autoUpdates
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .filter(_.ecuSerial === ecuSerial)
      .map(_.targetName)
      .result
  }

  def findByTargetNameAction(namespace: Namespace, targetName: TargetName): DBIO[Seq[(DeviceId, HardwareIdentifier, TargetUpdate)]] = {
    Schema.autoUpdates
      .filter(_.namespace === namespace)
      .filter(_.targetName === targetName)
      .join(Schema.ecu.filter(_.namespace === namespace)).on(_.ecuSerial === _.ecuSerial)
      .join(Schema.currentImage.filter(_.namespace === namespace)).on(_._1.ecuSerial === _.id)
      .map{case ((auto, ecu), current) =>
        (auto.device, ecu.hardwareId, current.filepath, current.checksum, current.length)}
      .result
      .map(_.map{case (device, hw, filepath, checksum, length) => (device, hw, TargetUpdate(filepath, checksum, length, None))})
  }

  def findByTargetName(namespace: Namespace, targetName: TargetName): Future[Map[DeviceId, Seq[(HardwareIdentifier, TargetUpdate)]]] = db.run {
    findByTargetNameAction(namespace, targetName)
      .map(_.groupBy{case (device, _, _) => device}
             .map{case (k, v) => k -> v.map{case (_, hw, tu) => (hw, tu)}})
  }
}
