package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, HashMethod, RepoId}
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.KeyType
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import io.circe.Json
import slick.driver.MySQLDriver.api._

object Schema {
  import com.advancedtelematic.libats.db.SlickAnyVal._
  import com.advancedtelematic.libats.codecs.SlickRefined._
  import com.advancedtelematic.libtuf.data.SlickCirceMapper.{checksumMapper, jsonMapper}

  type EcuRow = (EcuSerial, DeviceId, Namespace, Boolean, KeyType, String)
  class EcuTable(tag: Tag) extends Table[Ecu](tag, "Ecu") {
    def ecuSerial = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def device = column[DeviceId]("device")
    def namespace = column[Namespace]("namespace")
    def primary = column[Boolean]("primary")
    def cryptoMethod = column[KeyType]("cryptographic_method")
    def publicKey = column[String]("public_key")

    override def * = (ecuSerial, device, namespace, primary, cryptoMethod, publicKey) <>
      ((x: EcuRow) => Ecu(x._1, x._2, x._3, x._4, Crypto(x._5, x._6)),
       (x: Ecu) => Some((x.ecuSerial, x.device, x.namespace, x.primary, x.crypto.method, x.crypto.publicKey))
       )
  }
  protected [db] val ecu = TableQuery[EcuTable]

  type CurrentImageRow = (EcuSerial, String, Int, Checksum, String)
  class CurrentImageTable(tag: Tag) extends Table[CurrentImage](tag, "CurrentImage") { // TODO: Lets use snake case + plurarl for table names
    def id = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def filepath = column[String]("filepath")
    def length = column[Int]("length")
    def checksum = column[Checksum]("checksum")
    def attacksDetected = column[String]("attacks_detected")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    // I think this might be more readable? I don't like so many underscores on tuples gets hard to read.
    override def * = (id, filepath, length, checksum, attacksDetected) <> (
      (_: CurrentImageRow) match {
        case (id, filepath, length, checksum, attacksDetected) =>
          CurrentImage(id, Image(filepath, FileInfo(Map(checksum.method -> checksum.hash), length)), attacksDetected)
      },
      (x: CurrentImage) => Some((x.ecuSerial, x.image.filepath, x.image.fileinfo.length, Checksum(HashMethod.SHA256, x.image.fileinfo.hashes(HashMethod.SHA256)), x.attacksDetected))
    )
  }

  protected [db] val currentImage = TableQuery[CurrentImageTable]

  // TODO: All relations are mapping, this should be repo_names (table name)?
  class RepoNameTable(tag: Tag) extends Table[(Namespace, RepoId)](tag, "RepoNameMapping") {
    def ns = column[Namespace]("namespace", O.PrimaryKey)
    def repo = column[RepoId]("repoName")

    override def * = (ns, repo) // I'd avoid tuples, but might make sense here
  }
  protected [db] val repoNameMapping = TableQuery[RepoNameTable]

  type EcuTargetRow = (Int, EcuSerial, String, Int, Checksum)
  class EcuTargetTable(tag: Tag) extends Table[EcuTarget](tag, "EcuTarget") {
    def version = column[Int]("version")
    def id = column[EcuSerial]("ecu_serial")
    def filepath = column[String]("filepath")
    def length = column[Int]("length")
    def checksum = column[Checksum]("checksum")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    def primKey = primaryKey("ecu_target_pk", (version, id))

    // same here
    override def * = (version, id, filepath, length, checksum) <>
      ((x: EcuTargetRow) => EcuTarget(x._1, x._2, Image(x._3, FileInfo(Map(x._5.method -> x._5.hash), x._4))),
       (x: EcuTarget) => Some((x.version, x.ecuIdentifier, x.image.filepath, x.image.fileinfo.length, Checksum(HashMethod.SHA256, x.image.fileinfo.hashes(HashMethod.SHA256)))))
  }
  protected [db] val ecuTargets = TableQuery[EcuTargetTable]

  class DeviceTargetsTable(tag: Tag) extends Table[DeviceTargets](tag, "DeviceTargets") {
    def device = column[DeviceId]("device", O.PrimaryKey)
    def latestScheduledTarget = column[Int]("latest_scheduled_target")

    override def * = (device, latestScheduledTarget) <>
      ((DeviceTargets.apply _).tupled, DeviceTargets.unapply)
  }
  protected [db] val deviceTargets = TableQuery[DeviceTargetsTable]

  class DeviceCurrentTargetTable(tag: Tag) extends Table[DeviceCurrentTarget](tag, "DeviceCurrentTarget") {
    def device = column[DeviceId]("device", O.PrimaryKey)
    def deviceCurrentTarget = column[Int]("device_current_target")

    override def * = (device, deviceCurrentTarget) <>
      ((DeviceCurrentTarget.apply _).tupled, DeviceCurrentTarget.unapply)
  }
  protected [db] val deviceCurrentTarget = TableQuery[DeviceCurrentTargetTable]

  class FileCacheTable(tag: Tag) extends Table[FileCache](tag, "FileCache") {
    def role    = column[RoleType]("role")
    def version = column[Int]("version")
    def device  = column[DeviceId]("device")
    def fileEntity = column[Json]("fileEntity")

    def primKey = primaryKey("file_cache_pk", (role, version, device))

    override def * = (role, version, device, fileEntity) <>
      ((FileCache.apply _).tupled, FileCache.unapply)
  }
  protected [db] val fileCache = TableQuery[FileCacheTable]

  class FileCacheRequestTable(tag: Tag) extends Table[FileCacheRequest](tag, "FileCacheRequest") {
    def namespace = column[Namespace]("namespace")
    def version = column[Int]("version")
    def device = column[DeviceId]("device")
    def status = column[FileCacheRequestStatus.Status]("status")

    def primKey = primaryKey("file_cache_request_pk", (version, device))

    override def * = (namespace, version, device, status) <>
      ((FileCacheRequest.apply _).tupled, FileCacheRequest.unapply)
  }
  protected [db] val fileCacheRequest = TableQuery[FileCacheRequestTable]
}
