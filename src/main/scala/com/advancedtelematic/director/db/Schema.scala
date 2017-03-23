package com.advancedtelematic.director.db

import java.security.PublicKey

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.HashMethod.HashMethod
import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, HashMethod, RepoId, ValidChecksum}
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.KeyType
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import eu.timepit.refined.api.Refined
import io.circe.Json
import slick.driver.MySQLDriver.api._

object Schema {
  import com.advancedtelematic.libats.codecs.SlickRefined._
  import com.advancedtelematic.libtuf.data.SlickCirceMapper.{checksumMapper, jsonMapper}
  import com.advancedtelematic.libtuf.data.SlickPublicKeyMapper._
  import com.advancedtelematic.libtuf.data.SlickUriMapper._
  import com.advancedtelematic.libats.db.SlickAnyVal._

  type EcuRow = (EcuSerial, DeviceId, Namespace, Boolean, KeyType, PublicKey)
  class EcusTable(tag: Tag) extends Table[Ecu](tag, "ecus") {
    def ecuSerial = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def device = column[DeviceId]("device")
    def namespace = column[Namespace]("namespace")
    def primary = column[Boolean]("primary")
    def cryptoMethod = column[KeyType]("cryptographic_method")
    def publicKey = column[PublicKey]("public_key")

    override def * = (ecuSerial, device, namespace, primary, cryptoMethod, publicKey) <>
      ((x: EcuRow) => Ecu(x._1, x._2, x._3, x._4, ClientKey(x._5, x._6)),
       (x: Ecu) => Some((x.ecuSerial, x.device, x.namespace, x.primary, x.clientKey.keytype, x.clientKey.keyval))
       )
  }
  protected [db] val ecu = TableQuery[EcusTable]

  type CurrentImageRow = (EcuSerial, String, Long, Checksum, String)
  class CurrentImagesTable(tag: Tag) extends Table[CurrentImage](tag, "current_images") {
    def id = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def filepath = column[String]("filepath")
    def length = column[Long]("length")
    def checksum = column[Checksum]("checksum")
    def attacksDetected = column[String]("attacks_detected")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    override def * = (id, filepath, length, checksum, attacksDetected) <> (
      (_: CurrentImageRow) match {
        case (id, filepath, length, checksum, attacksDetected) =>
          CurrentImage(id, Image(filepath, FileInfo(Map(checksum.method -> checksum.hash), length)), attacksDetected)
      },
      (x: CurrentImage) => Some((x.ecuSerial, x.image.filepath, x.image.fileinfo.length, Checksum(HashMethod.SHA256, x.image.fileinfo.hashes(HashMethod.SHA256)), x.attacksDetected))
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

  type EcuTargetRow = (Int, EcuSerial, String, Long, Checksum, Uri)
  class EcuTargetsTable(tag: Tag) extends Table[EcuTarget](tag, "ecu_targets") {
    def version = column[Int]("version")
    def id = column[EcuSerial]("ecu_serial")
    def filepath = column[String]("filepath")
    def length = column[Long]("length")
    def checksum = column[Checksum]("checksum")
    def uri = column[Uri]("uri")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    def primKey = primaryKey("ecu_target_pk", (version, id))

    override def * = (version, id, filepath, length, checksum, uri) <> (
      (_: EcuTargetRow) match {
        case (version, id, filepath, length, checksum, uri) =>
          EcuTarget(version, id, CustomImage(filepath, FileInfo(Map(checksum.method -> checksum.hash), length), uri))
      },
      (x: EcuTarget) => Some((x.version, x.ecuIdentifier, x.image.filepath, x.image.fileinfo.length, Checksum(HashMethod.SHA256, x.image.fileinfo.hashes(HashMethod.SHA256)), x.image.uri)))
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

    def primKey = primaryKey("file_cache_pk", (role, version, device))

    override def * = (role, version, device, fileEntity) <>
      ((FileCache.apply _).tupled, FileCache.unapply)
  }
  protected [db] val fileCache = TableQuery[FileCacheTable]

  class FileCacheRequestsTable(tag: Tag) extends Table[FileCacheRequest](tag, "file_cache_requests") {
    def namespace = column[Namespace]("namespace")
    def targetVersion = column[Int]("target_version")
    def device = column[DeviceId]("device")
    def status = column[FileCacheRequestStatus.Status]("status")
    def timestampVersion = column[Int]("timestamp_version")

    def primKey = primaryKey("file_cache_request_pk", (timestampVersion, device))

    override def * = (namespace, targetVersion, device, status, timestampVersion) <>
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

  implicit val hashMethodColumn = MappedColumnType.base[HashMethod, String](_.value.toString, HashMethod.withName)
  type MTURow = (UpdateId, String, String, HashMethod, Refined[String, ValidChecksum], Long, Namespace)
  class MultiTargetUpdates(tag: Tag) extends Table[MultiTargetUpdate](tag, "multi_target_updates") {
    def id = column[UpdateId]("id")
    def hardwareId = column[String]("hardware_identifier")
    def target = column[String]("target")
    def hashMethod = column[HashMethod]("hash_method")
    def targetHash = column[Refined[String, ValidChecksum]]("target_hash")
    def targetSize = column[Long]("target_size")
    def namespace = column[Namespace]("namespace")

    def * = (id, hardwareId, target, hashMethod, targetHash, targetSize, namespace).shaped <>
      ((x: MTURow) => MultiTargetUpdate(x._1, x._2, x._3, Checksum(x._4, x._5), x._6, x._7),
       (x: MultiTargetUpdate) =>
         Some((x.id, x.hardwareId, x.target, x.checksum.method, x.checksum.hash, x.targetLength, x.namespace)))

    def pk = primaryKey("mtu_pk", (id, hardwareId))
  }

  protected [db] val multiTargets = TableQuery[MultiTargetUpdates]
}
