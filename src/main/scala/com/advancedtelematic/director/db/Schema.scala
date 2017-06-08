package com.advancedtelematic.director.db

import java.security.PublicKey

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.{FileCacheRequestStatus, LaunchedMultiTargetUpdateStatus, UpdateType}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, HashMethod, TargetFilename, UpdateId, ValidChecksum}
import com.advancedtelematic.libats.messaging_datatype.DataType.HashMethod.HashMethod
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, RepoId}
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.KeyType
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import eu.timepit.refined.api.Refined
import io.circe.Json
import java.time.Instant
import slick.jdbc.MySQLProfile.api._

object Schema {
  import com.advancedtelematic.libats.slick.codecs.SlickRefined._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._
  import com.advancedtelematic.libats.slick.db.SlickCirceMapper.jsonMapper
  import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
  import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
  import com.advancedtelematic.libats.slick.db.SlickUriMapper._
  import com.advancedtelematic.libtuf.data.TufSlickMappings._

  type EcuRow = (EcuSerial, DeviceId, Namespace, Boolean, HardwareIdentifier, KeyType, PublicKey)
  class EcusTable(tag: Tag) extends Table[Ecu](tag, "ecus") {
    def ecuSerial = column[EcuSerial]("ecu_serial")
    def device = column[DeviceId]("device")
    def namespace = column[Namespace]("namespace")
    def primary = column[Boolean]("primary")
    def hardwareId = column[HardwareIdentifier]("hardware_identifier")
    def cryptoMethod = column[KeyType]("cryptographic_method")
    def publicKey = column[PublicKey]("public_key")

    def primKey = primaryKey("ecus_pk", (namespace, ecuSerial))

    override def * = (ecuSerial, device, namespace, primary, hardwareId, cryptoMethod, publicKey) <>
      ((_ : EcuRow) match {
         case (ecuSerial, device, namespace, primary, hardwareId, cryptoMethod, publicKey) =>
           Ecu(ecuSerial, device, namespace, primary, hardwareId, ClientKey(cryptoMethod, publicKey))
       },
       (x: Ecu) => Some((x.ecuSerial, x.device, x.namespace, x.primary, x.hardwareId, x.clientKey.keytype, x.clientKey.keyval))
       )
  }
  protected [db] val ecu = TableQuery[EcusTable]

  type CurrentImageRow = (Namespace, EcuSerial, TargetFilename, Long, Checksum, String)
  class CurrentImagesTable(tag: Tag) extends Table[CurrentImage](tag, "current_images") {
    def namespace = column[Namespace]("namespace")
    def id = column[EcuSerial]("ecu_serial")
    def filepath = column[TargetFilename]("filepath")
    def length = column[Long]("length")
    def checksum = column[Checksum]("checksum")
    def attacksDetected = column[String]("attacks_detected")

    def primKey = primaryKey("current_image_pk", (namespace, id))

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    override def * = (namespace, id, filepath, length, checksum, attacksDetected) <> (
      (_: CurrentImageRow) match {
        case (namespace, id, filepath, length, checksum, attacksDetected) =>
          CurrentImage(namespace, id, Image(filepath, FileInfo(Map(checksum.method -> checksum.hash), length)), attacksDetected)
      },
      (x: CurrentImage) => Some((x.namespace, x.ecuSerial, x.image.filepath, x.image.fileinfo.length,
                                 Checksum(HashMethod.SHA256, x.image.fileinfo.hashes(HashMethod.SHA256)), x.attacksDetected))
    )
  }

  protected [db] val currentImage = TableQuery[CurrentImagesTable]

  class RepoNameTable(tag: Tag) extends Table[RepoName](tag, "repo_names") {
    def ns = column[Namespace]("namespace", O.PrimaryKey)
    def repo = column[RepoId]("repo_id")

    override def * = (ns, repo) <>
      ((RepoName.apply _).tupled, RepoName.unapply)
  }
  protected [db] val repoNames = TableQuery[RepoNameTable]

  class EcuTargetsTable(tag: Tag) extends Table[EcuTarget](tag, "ecu_targets") {
    def namespace  = column[Namespace]("namespace")
    def version = column[Int]("version")
    def id = column[EcuSerial]("ecu_serial")
    def filepath = column[TargetFilename]("filepath")
    def length = column[Long]("length")
    def checksum = column[Checksum]("checksum")
    def uri = column[Uri]("uri")

    def diffHashMethod = column[Option[HashMethod]]("diff_hash_method")
    def diffHash = column[Option[Refined[String,ValidChecksum]]]("diff_hash")
    def diffSize = column[Option[Long]]("diff_size")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    def primKey = primaryKey("ecu_target_pk", (namespace, version, id))

    override def * = (namespace, version, id, filepath, length, checksum, uri, diffHashMethod, diffHash, diffSize) <>
      ({ case (namespace, version, id, filepath, length, checksum, uri, diffHashMethod, diffHash, diffSize) =>
           val delta = for {
             method <- diffHashMethod
             hash <- diffHash
             size <- diffSize
           } yield DiffInfo(Checksum(method, hash), size)

           EcuTarget(namespace, version, id, Image(filepath, FileInfo(Map(checksum.method -> checksum.hash), length)), uri, delta)
       },
       (x: EcuTarget) => Some((x.namespace, x.version, x.ecuIdentifier, x.image.filepath, x.image.fileinfo.length,
                               Checksum(HashMethod.SHA256, x.image.fileinfo.hashes(HashMethod.SHA256)), x.uri,
                               x.diff.map(_.checksum.method), x.diff.map(_.checksum.hash), x.diff.map(_.length)))
      )
  }
  protected [db] val ecuTargets = TableQuery[EcuTargetsTable]

  class DeviceUpdateTargetsTable(tag: Tag) extends Table[DeviceUpdateTarget](tag, "device_update_targets") {
    def device = column[DeviceId]("device")
    def update = column[Option[UpdateId]]("update_uuid")
    def version = column[Int]("version")

    def primKey = primaryKey("device_targets_pk", (device, version))

    override def * = (device, update, version) <>
      ((DeviceUpdateTarget.apply _).tupled, DeviceUpdateTarget.unapply)
  }
  protected [db] val deviceTargets = TableQuery[DeviceUpdateTargetsTable]

  class DeviceCurrentTargetTable(tag: Tag) extends Table[DeviceCurrentTarget](tag, "device_current_target") {
    def device = column[DeviceId]("device", O.PrimaryKey)
    def deviceCurrentTarget = column[Int]("device_current_target")

    override def * = (device, deviceCurrentTarget) <>
      ((DeviceCurrentTarget.apply _).tupled, DeviceCurrentTarget.unapply)
  }
  protected [db] val deviceCurrentTarget = TableQuery[DeviceCurrentTargetTable]

  class FileCacheTable(tag: Tag) extends Table[FileCache](tag, "file_cache") {
    def role    = column[RoleType]("role")
    def version = column[Int]("version")
    def device  = column[DeviceId]("device")
    def fileEntity = column[Json]("file_entity")
    def expires  = column[Instant]("expires")

    def primKey = primaryKey("file_cache_pk", (role, version, device))

    override def * = (role, version, device, expires, fileEntity) <>
      ((FileCache.apply _).tupled, FileCache.unapply)
  }
  protected [db] val fileCache = TableQuery[FileCacheTable]

  class FileCacheRequestsTable(tag: Tag) extends Table[FileCacheRequest](tag, "file_cache_requests") {
    def namespace = column[Namespace]("namespace")
    def targetVersion = column[Int]("target_version")
    def device = column[DeviceId]("device")
    def update = column[Option[UpdateId]]("update_uuid")
    def status = column[FileCacheRequestStatus.Status]("status")
    def timestampVersion = column[Int]("timestamp_version")

    def primKey = primaryKey("file_cache_request_pk", (timestampVersion, device))

    override def * = (namespace, targetVersion, device, update, status, timestampVersion) <>
      ((FileCacheRequest.apply _).tupled, FileCacheRequest.unapply)
  }
  protected [db] val fileCacheRequest = TableQuery[FileCacheRequestsTable]

  class RootFilesTable(tag: Tag) extends Table[RootFile](tag, "root_files") {
    def namespace = column[Namespace]("namespace")
    def root = column[Json]("root_file")

    def pk = primaryKey("root_files_pk", namespace)

    override def * = (namespace, root) <>
      ((RootFile.apply _).tupled, RootFile.unapply)
  }
  protected [db] val rootFiles = TableQuery[RootFilesTable]

  implicit val hashMethodColumn = MappedColumnType.base[HashMethod, String](_.toString, HashMethod.withName)
  type MTURow = (UpdateId, HardwareIdentifier, TargetFilename, HashMethod, Refined[String, ValidChecksum], Long,
                 Option[TargetFilename], Option[HashMethod], Option[Refined[String, ValidChecksum]], Option[Long], Namespace)
  class MultiTargetUpdates(tag: Tag) extends Table[MultiTargetUpdate](tag, "multi_target_updates") {
    def id = column[UpdateId]("id")
    def hardwareId = column[HardwareIdentifier]("hardware_identifier")
    def target = column[TargetFilename]("target")
    def hashMethod = column[HashMethod]("hash_method")
    def targetHash = column[Refined[String, ValidChecksum]]("target_hash")
    def targetSize = column[Long]("target_size")
    def fromTarget = column[Option[TargetFilename]]("from_target")
    def fromHashMethod = column[Option[HashMethod]]("from_hash_method")
    def fromTargetHash = column[Option[Refined[String, ValidChecksum]]]("from_target_hash")
    def fromTargetSize = column[Option[Long]]("from_target_size")
    def namespace = column[Namespace]("namespace")

    private def fromTargetOption(mtarget: Option[TargetFilename], mhashMethod: Option[HashMethod],
                                 mhash: Option[Refined[String, ValidChecksum]], msize: Option[Long]): Option[TargetUpdate] = for {
      target <- mtarget
      hashMethod <- mhashMethod
      hash <- mhash
      size <- msize
    } yield TargetUpdate(target, Checksum(hashMethod, hash), size)

    def * = (id, hardwareId, target, hashMethod, targetHash, targetSize, fromTarget, fromHashMethod, fromTargetHash, fromTargetSize, namespace) <> (
      (_: MTURow) match {
        case (id, hardwareId, target, hashMethod, targetHash, targetSize, fromTarget, fromHashMethod, fromTargetHash, fromTargetSize, namespace) =>
          val from = fromTargetOption(fromTarget, fromHashMethod, fromTargetHash, fromTargetSize)
          MultiTargetUpdate(id, hardwareId, from, target, Checksum(hashMethod, targetHash), targetSize, namespace)
      }, (x: MultiTargetUpdate) => {
        def from[T](fn: TargetUpdate => T): Option[T] = x.fromTarget.map(fn)
        Some((x.id, x.hardwareId, x.target, x.checksum.method, x.checksum.hash, x.targetLength,
              from(_.target), from(_.checksum.method), from(_.checksum.hash), from(_.targetLength), x.namespace))
      }
    )

    def pk = primaryKey("mtu_pk", (id, hardwareId))
  }

  protected [db] val multiTargets = TableQuery[MultiTargetUpdates]

  class MultiTargetUpdateDiffTable(tag: Tag) extends Table[MultiTargetUpdateDiff](tag, "multi_target_update_diffs") {
    def id = column[UpdateId]("id")
    def hardwareId = column[HardwareIdentifier]("hardware_identifier")
    def diffHashMethod = column[HashMethod]("diff_hash_method")
    def diffHash = column[Refined[String, ValidChecksum]]("diff_hash")
    def diffSize = column[Long]("diff_size")

    def pk = primaryKey("mtu_delta_pk", (id, hardwareId))

    override def * = (id, hardwareId, diffHashMethod, diffHash, diffSize) <> (
      { case (id, hardwareId, diffHashMethod, diffHash, diffSize) =>
          MultiTargetUpdateDiff(id, hardwareId, Checksum(diffHashMethod, diffHash), diffSize)
      }, (x: MultiTargetUpdateDiff) => Some((x.id, x.hardwareId, x.checksum.method, x.checksum.hash, x.size))
      )
  }

  protected [db] val multiTargetDiffs = TableQuery[MultiTargetUpdateDiffTable]

  class LaunchedMultiTargetUpdatesTable(tag: Tag) extends Table[LaunchedMultiTargetUpdate](tag, "launched_multi_target_updates") {
    def device = column[DeviceId]("device")
    def update = column[UpdateId]("update_id")
    def timestampVersion = column[Int]("timestamp_version")
    def status = column[LaunchedMultiTargetUpdateStatus.Status]("status")

    def primKey = primaryKey("launched_multi_target_updates_pk", (device, update, timestampVersion))

    override def * = (device, update, timestampVersion, status) <>
      ((LaunchedMultiTargetUpdate.apply _).tupled, LaunchedMultiTargetUpdate.unapply)
  }

  protected [db] val launchedMultiTargetUpdates = TableQuery[LaunchedMultiTargetUpdatesTable]

  class UpdateTypes(tag: Tag) extends Table[(UpdateId, UpdateType.UpdateType)](tag, "update_types") {
    def update = column[UpdateId]("update_id", O.PrimaryKey)
    def updateType = column[UpdateType.UpdateType]("update_type")

    override def * = (update, updateType)
  }

  protected [db] val updateTypes = TableQuery[UpdateTypes]
}
