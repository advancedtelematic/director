package com.advancedtelematic.director.db

import java.time.Instant

import akka.http.scaladsl.model.Uri
import cats.implicits._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.FileCacheRequestStatus.FileCacheRequestStatus
import com.advancedtelematic.libats.data.DataType.HashMethod.HashMethod
import com.advancedtelematic.libats.data.DataType.{Checksum, CorrelationId, HashMethod, Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, TargetFilename, TargetName, TufKey}
import eu.timepit.refined.api.Refined
import io.circe.Json
import slick.jdbc.MySQLProfile.api._


object Schema {
  import SlickMapping._
  import com.advancedtelematic.libats.slick.codecs.SlickRefined._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._
  import com.advancedtelematic.libats.slick.db.SlickCirceMapper.jsonMapper
  import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
  import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
  import com.advancedtelematic.libats.slick.db.SlickUriMapper._
  import com.advancedtelematic.libats.slick.db.SlickUrnMapper._
  import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
  import com.advancedtelematic.libtuf_server.data.TufSlickMappings._

  class EcusTable(tag: Tag) extends Table[Ecu](tag, "ecus") {
    def ecuSerial = column[EcuIdentifier]("ecu_serial")
    def device = column[DeviceId]("device")
    def namespace = column[Namespace]("namespace")
    def primary = column[Boolean]("primary")
    def hardwareId = column[HardwareIdentifier]("hardware_identifier")
    def publicKey = column[TufKey]("public_key")

    def createdAt = column[Instant]("created_at")

    def primKey = primaryKey("ecus_pk", (namespace, ecuSerial))

    override def * = (ecuSerial, device, namespace, primary, hardwareId, publicKey) <> ((Ecu.apply _).tupled, Ecu.unapply)
  }
  protected [db] val ecu = TableQuery[EcusTable]

  type CurrentImageRow = (Namespace, EcuIdentifier, TargetFilename, Long, Checksum, String)
  class CurrentImagesTable(tag: Tag) extends Table[CurrentImage](tag, "current_images") {
    def namespace = column[Namespace]("namespace")
    def id = column[EcuIdentifier]("ecu_serial")
    def filepath = column[TargetFilename]("filepath")
    def length = column[Long]("length")
    def checksum = column[Checksum]("checksum")
    def attacksDetected = column[String]("attacks_detected")

    def primKey = primaryKey("current_image_pk", (namespace, id))

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    def fileInfo = (checksum, length) <>
      ( { case (checksum, length) => FileInfo(Hashes(checksum.hash), length)},
        (x: FileInfo) => Some((Checksum(HashMethod.SHA256, x.hashes.sha256), x.length))
      )
    def image = (filepath, fileInfo) <> ((Image.apply _).tupled, Image.unapply)

    override def * = (namespace, id, image, attacksDetected) <> ((CurrentImage.apply _).tupled, CurrentImage.unapply)
  }

  protected [db] val currentImage = TableQuery[CurrentImagesTable]

  class RepoNameTable(tag: Tag) extends Table[RepoName](tag, "repo_names") {
    def ns = column[Namespace]("namespace", O.PrimaryKey)
    def repo = column[RepoId]("repo_id")

    override def * = (ns, repo) <> ((RepoName.apply _).tupled, RepoName.unapply)
  }
  protected [db] val repoNames = TableQuery[RepoNameTable]

  class EcuTargetsTable(tag: Tag) extends Table[EcuTarget](tag, "ecu_targets") {
    def namespace  = column[Namespace]("namespace")
    def version = column[Int]("version")
    def id = column[EcuIdentifier]("ecu_serial")
    def filepath = column[TargetFilename]("filepath")
    def length = column[Long]("length")
    def checksum = column[Checksum]("checksum")
    def uri = column[Uri]("uri")
    def diffFormat = column[Option[TargetFormat]]("diff_format")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    def primKey = primaryKey("ecu_target_pk", (namespace, version, id))

    def fileInfo = (checksum, length) <>
      ( { case (checksum, length) => FileInfo(Hashes(checksum.hash), length)},
        (x: FileInfo) => Some((Checksum(HashMethod.SHA256, x.hashes.sha256), x.length))
      )

    def image = (filepath, fileInfo) <> ((Image.apply _).tupled, Image.unapply)

    def customImage = (image, uri, diffFormat) <> ((CustomImage.apply _).tupled, CustomImage.unapply)

    override def * = (namespace, version, id, customImage) <> ((EcuTarget.apply _).tupled, EcuTarget.unapply)
  }
  protected [db] val ecuTargets = TableQuery[EcuTargetsTable]

  class DeviceUpdateTargetsTable(tag: Tag) extends Table[DeviceUpdateTarget](tag, "device_update_targets") {
    def device = column[DeviceId]("device")
    def correlationId = column[Option[CorrelationId]]("correlation_id")
    def update = column[Option[UpdateId]]("update_uuid")
    def version = column[Int]("version")
    def served = column[Boolean]("served")

    def primKey = primaryKey("device_targets_pk", (device, version))

    override def * = (device, correlationId, update, version, served) <> ((DeviceUpdateTarget.apply _).tupled, DeviceUpdateTarget.unapply)
  }
  protected [db] val deviceTargets = TableQuery[DeviceUpdateTargetsTable]

  class DeviceCurrentTargetTable(tag: Tag) extends Table[DeviceCurrentTarget](tag, "device_current_target") {
    def device = column[DeviceId]("device", O.PrimaryKey)
    def deviceCurrentTarget = column[Int]("device_current_target")

    override def * = (device, deviceCurrentTarget) <> ((DeviceCurrentTarget.apply _).tupled, DeviceCurrentTarget.unapply)
  }
  protected [db] val deviceCurrentTarget = TableQuery[DeviceCurrentTargetTable]

  class FileCacheTable(tag: Tag) extends Table[FileCache](tag, "file_cache") {
    def role    = column[RoleType]("role")
    def version = column[Int]("version")
    def device  = column[DeviceId]("device")
    def fileEntity = column[Json]("file_entity")
    def expires = column[Instant]("expires")

    def primKey = primaryKey("file_cache_pk", (role, version, device))

    override def * = (role, version, device, expires, fileEntity) <> ((FileCache.apply _).tupled, FileCache.unapply)
  }
  protected [db] val fileCache = TableQuery[FileCacheTable]

  class FileCacheRequestsTable(tag: Tag) extends Table[FileCacheRequest](tag, "file_cache_requests") {
    def namespace = column[Namespace]("namespace")
    def targetVersion = column[Int]("target_version")
    def device = column[DeviceId]("device")
    def status = column[FileCacheRequestStatus]("status")
    def timestampVersion = column[Int]("timestamp_version")
    def correlationId = column[Option[CorrelationId]]("correlation_id")

    def primKey = primaryKey("file_cache_request_pk", (timestampVersion, device))

    override def * = (namespace, targetVersion, device, status, timestampVersion, correlationId) <>
      ((FileCacheRequest.apply _).tupled, FileCacheRequest.unapply)
  }
  protected [db] val fileCacheRequest = TableQuery[FileCacheRequestsTable]

  class MultiTargetUpdates(tag: Tag) extends Table[MultiTargetUpdateRow](tag, "multi_target_updates") {
    def id = column[UpdateId]("id")
    def hardwareId = column[HardwareIdentifier]("hardware_identifier")
    def toTarget = column[TargetFilename]("target")
    def toHashMethod = column[HashMethod]("hash_method")
    def toTargetHash = column[Refined[String, ValidChecksum]]("target_hash")
    def toTargetSize = column[Long]("target_size")
    def fromTarget = column[Option[TargetFilename]]("from_target")
    def fromHashMethod = column[Option[HashMethod]]("from_hash_method")
    def fromTargetHash = column[Option[Refined[String, ValidChecksum]]]("from_target_hash")
    def fromTargetSize = column[Option[Long]]("from_target_size")
    def targetFormat = column[TargetFormat]("target_format")
    def generateDiff = column[Boolean]("generate_diff")
    def namespace = column[Namespace]("namespace")

    def fromTargetChecksum: Rep[Option[Checksum]] = (fromHashMethod, fromTargetHash) <> (
      { case (hashMethod, hash) => (hashMethod, hash).mapN(Checksum)},
      (x: Option[Checksum]) => Some((x.map(_.method), x.map(_.hash)))
      )

    def fromTargetUpdate = (fromTarget, fromTargetChecksum, fromTargetSize) <> (
      { case (target, checksum, size) => (target, checksum, size).mapN(TargetUpdate)},
      (x : Option[TargetUpdate]) => Some((x.map(_.target), x.map(_.checksum), x.map(_.targetLength)))
    )

    def toTargetChecksum: Rep[Checksum] = (toHashMethod, toTargetHash) <>
      ((Checksum.apply _).tupled, Checksum.unapply)

    def toTargetUpdate = (toTarget, toTargetChecksum, toTargetSize) <>
      ((TargetUpdate.apply _).tupled, TargetUpdate.unapply)

    def * = (id, hardwareId, fromTargetUpdate, toTargetUpdate, targetFormat, generateDiff, namespace) <>
      ((MultiTargetUpdateRow.apply _).tupled, MultiTargetUpdateRow.unapply)

    def pk = primaryKey("mtu_pk", (id, hardwareId))
  }

  protected [db] val multiTargets = TableQuery[MultiTargetUpdates]

  class AutoUpdates(tag: Tag) extends Table[AutoUpdate](tag, "auto_updates") {
    def namespace = column[Namespace]("namespace")
    def device = column[DeviceId]("device")
    def ecuSerial = column[EcuIdentifier]("ecu_serial")
    def targetName = column[TargetName]("target_name")

    override def * = (namespace, device, ecuSerial, targetName) <>
      ((AutoUpdate.apply _).tupled, AutoUpdate.unapply)
  }
  protected [db] val autoUpdates = TableQuery[AutoUpdates]
}
