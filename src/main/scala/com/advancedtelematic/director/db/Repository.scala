package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{Ecu, EcuTarget, FileCacheRequest, MultiTargetUpdate}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.data.DataType
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, TargetFilename, UpdateId}
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, HardwareIdentifier, RepoId, RoleType}
import io.circe.Json
import java.time.Instant

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

import scala.util.{Failure, Success}
import Errors._

trait AdminRepositorySupport {
  def adminRepository(implicit db: Database, ec: ExecutionContext) = new AdminRepository()
}

protected class AdminRepository()(implicit db: Database, ec: ExecutionContext) extends DeviceRepositorySupport with FileCacheRequestRepositorySupport {
  import com.advancedtelematic.director.data.AdminRequest.{EcuInfoResponse, EcuInfoImage, RegisterEcu, QueueResponse}
  import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceUpdateTarget, Image}
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._
  import com.advancedtelematic.libats.slick.codecs.SlickRefined._
  import com.advancedtelematic.libtuf.data.TufDataType.TufKey
  import com.advancedtelematic.libtuf.data.TufSlickMappings._

  implicit private class NotInCampaign(query: Query[Rep[DeviceId], DeviceId, Seq]) {
    def devTargets = Schema.deviceTargets
      .groupBy(_.device)
      .map{case (devId, q) => (devId, q.map(_.version).max.getOrElse(0))}

    def notInACampaign: Query[Rep[DeviceId], DeviceId, Seq] = {
      val reportedButNotInACampaign = query
        .join(Schema.deviceCurrentTarget).on(_ === _.device)
        .map{case (devId, devCurTarget) => (devId, devCurTarget.deviceCurrentTarget)}
        .joinLeft(devTargets).on(_._1 === _._1)
        .map{case ((devId, curTarg), devUpdate) => (devId, curTarg, devUpdate.map(_._2).getOrElse(curTarg))}
        .filter{ case(_, cur, lastScheduled) => cur === lastScheduled}
        .map(_._1)

      val notReported = query.filterNot(dev => dev.in(Schema.deviceCurrentTarget.map(_.device)))

      reportedButNotInACampaign.union(notReported)
    }
  }

  protected [db] def devicesNotInACampaign(devices: Seq[DeviceId]): Query[Rep[DeviceId], DeviceId, Seq] =
    Schema.ecu.map(_.device).filter(_.inSet(devices)).notInACampaign

  private def byDevice(namespace: Namespace, device: DeviceId): Query[Schema.EcusTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)

  protected [db] def fetchHwMappingAction(namespace: Namespace, device: DeviceId): DBIO[Map[EcuSerial, (HardwareIdentifier, Option[Image])]] =
    byDevice(namespace, device)
      .joinLeft(Schema.currentImage).on(_.ecuSerial === _.id)
      .map{case (x,y) => (x.ecuSerial, x.hardwareId, y)}
      .result
      .failIfEmpty(DeviceMissing)
      .map(_.map{case (id, hw, img) => id -> ((hw, img.map(_.image)))}.toMap)

  protected [db] def findImagesAction(namespace: Namespace, device: DeviceId): DBIO[Seq[(EcuSerial, Image)]] =
    byDevice(namespace, device)
      .map(_.ecuSerial)
      .join(Schema.currentImage).on(_ === _.id)
      .map(_._2)
      .result
      .map(_.map(cim => cim.ecuSerial -> cim.image))

  def findImages(namespace: Namespace, device: DeviceId): Future[Seq[(EcuSerial, Image)]] = db.run {
    findImagesAction(namespace, device)
  }

  def findAffected(namespace: Namespace, filepath: TargetFilename, offset: Long, limit: Long): Future[PaginationResult[DeviceId]] = db.run {
    Schema.currentImage
      .filter(_.filepath === filepath)
      .map(_.id)
      .join(Schema.ecu.filter(_.namespace === namespace)).on(_ === _.ecuSerial)
      .map(_._2)
      .map(_.device)
      .notInACampaign
      .distinct
      .paginateResult(offset = offset, limit = limit)
  }

  def findDevice(namespace: Namespace, device: DeviceId): Future[Seq[EcuInfoResponse]] = db.run {
    val query: Rep[Seq[(EcuSerial,HardwareIdentifier,Boolean,TargetFilename,Long,Checksum)]] = for {
      ecu <- Schema.ecu if ecu.namespace === namespace && ecu.device === device
      curImage <- Schema.currentImage if ecu.ecuSerial === curImage.id
    } yield (ecu.ecuSerial, ecu.hardwareId, ecu.primary, curImage.filepath, curImage.length, curImage.checksum)

    for {
      devices <- query.result.failIfEmpty(MissingDevice)
    } yield for {
      (id, hardwareId, primary, filepath, size, checksum) <- devices
    } yield EcuInfoResponse(id, hardwareId, primary, EcuInfoImage(filepath, size, Map(checksum.method -> checksum.hash)))
  }

  def findPublicKey(namespace: Namespace, device: DeviceId, ecu_serial: EcuSerial): Future[TufKey] = db.run {
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .filter(_.ecuSerial === ecu_serial)
      .map(x => (x.keyType, x.publicKey))
      .result
      .failIfNotSingle(MissingEcu)
      .map{case (typ, key) => TufCrypto.convert(typ, key)}
  }

  def findQueue(namespace: Namespace, device: DeviceId): Future[Seq[QueueResponse]] = db.run {
    def queueResult(version: Int, update: Option[UpdateId]): DBIO[QueueResponse] =
      fetchTargetVersionAction(namespace, device, version).map(QueueResponse(update,_))

    val versionOfDevice: DBIO[Int] = Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .result
      .failIfMany
      .map(_.getOrElse(0))

    def allUpdatesScheduledAfter(fromVersion: Int): DBIO[Seq[(Int, Option[UpdateId])]] = Schema.deviceTargets
      .filter(_.device === device)
      .filter(_.version > fromVersion)
      .sortBy(_.version)
      .map(x => (x.version, x.update))
      .result

    for {
      currentVersion <- versionOfDevice
      updates <- allUpdatesScheduledAfter(currentVersion)
      queue <- DBIO.sequence(updates.map((queueResult _).tupled))
    } yield queue
  }

  def createDevice(namespace: Namespace, device: DeviceId, primEcu: EcuSerial, ecus: Seq[RegisterEcu]): Future[Unit] = {
    val toClean = byDevice(namespace, device)
    val clean = Schema.currentImage.filter(_.id in toClean.map(_.ecuSerial)).delete.andThen(toClean.delete)

    def register(reg: RegisterEcu) =
      (Schema.ecu += Ecu(reg.ecu_serial, device, namespace, reg.ecu_serial == primEcu, reg.hardwareId, reg.clientKey))
        .handleIntegrityErrors(EcuAlreadyRegistered)

    val act = clean.andThen(DBIO.sequence(ecus.map(register)))
      .andThen(deviceRepository.createEmptyTarget(namespace, device))
    db.run(act.map(_ => ()).transactionally)
  }

  protected [db] def getLatestScheduledVersion(namespace: Namespace, device: DeviceId): DBIO[Int] =
    Schema.fileCacheRequest
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .map(_.timestampVersion)
      .forUpdate
      .max
      .result
      .failIfNone(NoTargetsScheduled)

  protected [db] def fetchUpdateIdAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Option[UpdateId]] =
    Schema.deviceTargets
      .filter(_.device === device)
      .filter(_.version === version)
      .map(_.update)
      .result
      .failIfMany
      .map(_.flatten)

  protected [db] def copyTargetsAction(namespace: Namespace, device: DeviceId, sourceVersion: Int, targetVersion: Int): DBIO[Unit] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .join(Schema.ecuTargets.filter(_.version === sourceVersion)).on(_.ecuSerial === _.id)
      .map(_._2)
      .result
      .flatMap { ecuTargets =>
        Schema.ecuTargets ++= ecuTargets.map(_.copy(version = targetVersion))
      }.map(_ => ())

  protected [db] def fetchTargetVersionAction(namespace: Namespace, device: DeviceId, version: Int): DBIO[Map[EcuSerial, CustomImage]] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .join(Schema.ecuTargets.filter(_.version === version)).on(_.ecuSerial === _.id)
      .map(_._2)
      .result
      .map(_.groupBy(_.ecuIdentifier).mapValues(_.head.image))

  def fetchTargetVersion(namespace: Namespace, device: DeviceId, version: Int): Future[Map[EcuSerial, CustomImage]] =
    db.run(fetchTargetVersionAction(namespace, device, version))

  protected [db] def storeTargetVersion(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId],
                                        version: Int, targets: Map[EcuSerial, CustomImage]): DBIO[Unit] = {
    val act = (Schema.ecuTargets
      ++= targets.map{ case (ecuSerial, image) => EcuTarget(namespace, version, ecuSerial, image)})

    act.map(_ => ()).transactionally
  }

  protected [db] def updateTargetAction(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId], targets: Map[EcuSerial, CustomImage]): DBIO[Int] = for {
    version <- getLatestScheduledVersion(namespace, device).asTry.flatMap {
      case Success(x) => DBIO.successful(x)
      case Failure(NoTargetsScheduled) => DBIO.successful(0)
      case Failure(ex) => DBIO.failed(ex)
    }
    previousMap <- fetchTargetVersionAction(namespace, device, version)
    new_version = version + 1
    new_targets = previousMap ++ targets
    _ <- storeTargetVersion(namespace, device, updateId, new_version, new_targets)
  } yield new_version

  def updateDeviceTargets(device: DeviceId, updateId: Option[UpdateId], version: Int): Future[DeviceUpdateTarget] = db.run {
    val target = DeviceUpdateTarget(device, updateId, version)
    (Schema.deviceTargets += target)
      .map(_ => target)
  }

  def updateTarget(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId], targets: Map[EcuSerial, CustomImage]): Future[Int] = db.run {
    updateTargetAction(namespace, device, updateId, targets).transactionally
  }

  def getPrimaryEcuForDevice(device: DeviceId): Future[EcuSerial] = db.run {
    Schema.ecu
      .filter(_.device === device)
      .filter(_.primary)
      .map(_.ecuSerial)
      .result
      .failIfNotSingle(DeviceMissingPrimaryEcu)
  }

  def getUpdatesFromTo(namespace: Namespace, device: DeviceId,
                       fromVersion: Int, toVersion: Int): Future[Seq[(Int, Option[UpdateId])]] = db.run {
    Schema.deviceTargets
      .filter(_.device === device)
      .filter(_.version > fromVersion)
      .filter(_.version <= toVersion)
      .map(x => (x.version, x.update))
      .sortBy(_._1)
      .result
  }

  def findAllHardwareIdentifiers(namespace: Namespace, offset: Long, limit: Long): Future[PaginationResult[HardwareIdentifier]] = db.run {
    Schema.ecu
      .filter(_.namespace === namespace)
      .map(_.hardwareId)
      .distinct
      .paginateAndSortResult(identity, offset = offset, limit = limit)
  }
}

trait DeviceRepositorySupport {
  def deviceRepository(implicit db: Database, ec: ExecutionContext) = new DeviceRepository()
}

protected class DeviceRepository()(implicit db: Database, ec: ExecutionContext) extends FileCacheRequestRepositorySupport {
  import com.advancedtelematic.director.data.AdminRequest.RegisterEcu
  import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._
  import com.advancedtelematic.libats.slick.codecs.SlickRefined._
  import DataType.{CurrentImage, DeviceCurrentTarget}

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
    val fcr = FileCacheRequest(namespace, 0, device, None, FileCacheRequestStatus.PENDING, 0)
    fileCacheRequestRepository.persistAction(fcr)
  }

  def create(namespace: Namespace, device: DeviceId, primEcu: EcuSerial, ecus: Seq[RegisterEcu]): Future[Unit] = {
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

  protected [db] def getCurrentVersionAction(device: DeviceId): DBIO[Option[Int]] =
    Schema.deviceCurrentTarget
      .filter(_.device === device)
      .map(_.deviceCurrentTarget)
      .result
      .failIfMany

  def getCurrentVersion(device: DeviceId): Future[Int] = db.run{
    getCurrentVersionAction(device)
      .failIfNone(MissingCurrentTarget)
  }

  protected [db] def getNextVersionAction(device: DeviceId): DBIO[Int] = {
    val devVer = getCurrentVersionAction(device)
      .failIfNone(MissingCurrentTarget)

    val targetVer = Schema.deviceTargets
      .filter(_.device === device)
      .map(_.version)
      .max
      .result
      .failIfNone(NoTargetsScheduled)

    devVer.zip(targetVer).map { case (device_version, target_version) =>
      scala.math.min(device_version + 1, target_version)
    }
  }

  def getNextVersion(device: DeviceId): Future[Int] = db.run(getNextVersionAction(device))

  protected [db] def updateDeviceVersionAction(device: DeviceId, device_version: Int): DBIO[Unit] = {
    Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, device_version))
      .map(_ => ())
  }

}

trait FileCacheRepositorySupport {
  def fileCacheRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRepository()
}

protected class FileCacheRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libtuf.data.ClientCodecs._
  import com.advancedtelematic.libtuf.data.ClientDataType.{SnapshotRole, TargetsRole, TimestampRole}
  import com.advancedtelematic.libats.slick.db.SlickCirceMapper.jsonMapper
  import com.advancedtelematic.libtuf.data.TufCodecs._
  import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
  import io.circe.syntax._
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
      .failIfNotSingle(MissingTimestamp)
  }.map (_.isBefore(Instant.now()))
}


trait FileCacheRequestRepositorySupport {
  def fileCacheRequestRepository(implicit db: Database, ec: ExecutionContext) = new FileCacheRequestRepository()
}

protected class FileCacheRequestRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.FileCacheRequestStatus._
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._
  import DataType.FileCacheRequest

  protected [db] def persistAction(req: FileCacheRequest): DBIO[Unit] =
    (Schema.fileCacheRequest += req)
      .map(_ => ())
      .handleIntegrityErrors(ConflictingFileCacheRequest)

  def persist(req: FileCacheRequest): Future[Unit] = db.run {
    persistAction(req)
  }

  def findPending(limit: Int = 10): Future[Seq[FileCacheRequest]] = db.run {
    Schema.fileCacheRequest.filter(_.status === PENDING).take(limit).result
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

  def findTargetVersion(namespace: Namespace, device: DeviceId, version: Int): Future[Int] = db.run {
    Schema.fileCacheRequest
      .filter(_.namespace === namespace)
      .filter(_.timestampVersion === version)
      .filter(_.device === device)
      .map(_.targetVersion)
      .result
      .failIfNotSingle(MissingFileCacheRequest)
  }
}

trait RepoNameRepositorySupport {
  def repoNameRepository(implicit db: Database, ec: ExecutionContext) = new RepoNameRepository()
}

protected class RepoNameRepository()(implicit db: Database, ec: ExecutionContext) {
  import DataType.RepoName
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libats.slick.db.SlickUUIDKey._

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
}

trait MultiTargetUpdatesRepositorySupport {
  def multiTargetUpdatesRepository(implicit db: Database, ec: ExecutionContext) = new MultiTargetUpdatesRepository()
}

protected class MultiTargetUpdatesRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libats.slick.codecs.SlickRefined._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._

  protected [db] def fetchAction(id: UpdateId, ns: Namespace): DBIO[Seq[MultiTargetUpdate]] =
    Schema.multiTargets
      .filter(_.id === id)
      .filter(_.namespace === ns)
      .result
      .failIfEmpty(MissingMultiTargetUpdate)

  def fetch(id: UpdateId, ns: Namespace): Future[Seq[MultiTargetUpdate]] = db.run {
    fetchAction(id, ns)
  }

  def create(rows: Seq[MultiTargetUpdate]): Future[Unit] = db.run {
    (Schema.multiTargets ++= rows)
      .handleIntegrityErrors(ConflictingMultiTargetUpdate)
      .map(_ => ())
  }
}

trait LaunchedMultiTargetUpdateRepositorySupport {
  def launchedMultiTargetUpdateRepository(implicit db: Database, ec: ExecutionContext) = new LaunchedMultiTargetUpdateRepository()
}

protected class LaunchedMultiTargetUpdateRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.DataType.LaunchedMultiTargetUpdate
  import com.advancedtelematic.director.data.LaunchedMultiTargetUpdateStatus
  import com.advancedtelematic.libats.slick.db.SlickExtensions._
  import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
  import Schema.launchedMultiTargetUpdates

  protected [db] def persistAction(lmtu: LaunchedMultiTargetUpdate): DBIO[LaunchedMultiTargetUpdate] =
    (launchedMultiTargetUpdates += lmtu)
      .handleIntegrityErrors(ConflictingLaunchedMultiTargetUpdate)
      .map(_ => lmtu)

  def persist(lmtu: LaunchedMultiTargetUpdate): Future[LaunchedMultiTargetUpdate] = db.run(persistAction(lmtu))

  def setStatus(device: DeviceId, updateId: UpdateId, timestampVersion: Int,
                status: LaunchedMultiTargetUpdateStatus.Status): Future[Unit] = db.run {
    launchedMultiTargetUpdates
      .filter(_.device === device)
      .filter(_.update === updateId)
      .filter(_.timestampVersion === timestampVersion)
      .map(_.status)
      .update(status)
      .handleSingleUpdateError(MissingLaunchedMultiTargetUpdate)
  }
}

trait UpdateTypesRepositorySupport {
  def updateTypesRepository(implicit db: Database, ec: ExecutionContext) = new UpdateTypesRepository()
}

protected class UpdateTypesRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.UpdateType.UpdateType
  import com.advancedtelematic.libats.slick.db.SlickExtensions._

  protected [db] def persistAction(updateId: UpdateId, ofType: UpdateType): DBIO[Unit] =
    (Schema.updateTypes += ((updateId, ofType)))
      .handleIntegrityErrors(ConflictingUpdateType)
      .map(_ => ())

  def getType(updateId: UpdateId): Future[UpdateType] = db.run {
    Schema.updateTypes
      .filter(_.update === updateId)
      .map(_.updateType)
      .result
      .failIfNotSingle(MissingUpdateType)
  }
}

