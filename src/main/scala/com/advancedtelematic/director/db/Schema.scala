package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libtuf.data.TufDataType.HashMethod
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.KeyType
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import io.circe.Json
import org.genivi.sota.data.{Namespace, Uuid}
import slick.driver.MySQLDriver.api._

object Schema {
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._
  import SlickCirceMapper._

  type EcuRow = (EcuSerial, Uuid, Namespace, Boolean, KeyType, String)
  class EcuTable(tag: Tag) extends Table[Ecu](tag, "Ecu") {
    def ecuSerial = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def device = column[Uuid]("device")
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

  type CurrentImageRow = (EcuSerial, String, Int, HexString, String)
  class CurrentImageTable(tag: Tag) extends Table[CurrentImage](tag, "CurrentImage") {
    def id = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def filepath = column[String]("filepath")
    def length = column[Int]("length")
    def sha256 = column[HexString]("sha256")
    def attacksDetected = column[String]("attacks_detected")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    override def * = (id, filepath, length, sha256, attacksDetected) <>
      ((p: CurrentImageRow) => CurrentImage(p._1, Image(p._2, FileInfo(Map(HashMethod.SHA256 -> p._4), p._3)), p._5),
      (x: CurrentImage) => Some((x.ecuSerial, x.image.filepath, x.image.fileinfo.length, x.image.fileinfo.hashes(HashMethod.SHA256), x.attacksDetected)))
  }
  protected [db] val currentImage = TableQuery[CurrentImageTable]

  class RepoNameTable(tag: Tag) extends Table[(Namespace, Uuid)](tag, "RepoNameMapping") {
    def ns = column[Namespace]("namespace", O.PrimaryKey)
    def repo = column[Uuid]("repoName")

    override def * = (ns, repo)
  }
  protected [db] val repoNameMapping = TableQuery[RepoNameTable]

  type EcuTargetRow = (Int, EcuSerial, String, Int, HexString)
  class EcuTargetTable(tag: Tag) extends Table[EcuTarget](tag, "EcuTarget") {
    def version = column[Int]("version")
    def id = column[EcuSerial]("ecu_serial")
    def filepath = column[String]("filepath")
    def length = column[Int]("length")
    def sha256 = column[HexString]("sha256")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    def primKey = primaryKey("ecu_target_pk", (version, id))

    override def * = (version, id, filepath, length, sha256) <>
      ((x: EcuTargetRow) => EcuTarget(x._1, x._2, Image(x._3, FileInfo(Map(HashMethod.SHA256 -> x._5), x._4))),
       (x: EcuTarget) => Some((x.version, x.ecuIdentifier, x.image.filepath, x.image.fileinfo.length, x.image.fileinfo.hashes(HashMethod.SHA256))))
  }
  protected [db] val ecuTargets = TableQuery[EcuTargetTable]

  class SnapshotTable(tag: Tag) extends Table[Snapshot](tag, "Snapshot") {
    def device = column[Uuid]("device", O.PrimaryKey)
    def device_version = column[Int]("device_version")
    def target_version = column[Int]("target_version")

    override def * = (device, device_version, target_version) <>
      ((Snapshot.apply _).tupled, Snapshot.unapply)
  }
  protected [db] val snapshots = TableQuery[SnapshotTable]

  class FileCacheTable(tag: Tag) extends Table[FileCache](tag, "FileCache") {
    def role    = column[RoleType]("role")
    def version = column[Int]("version")
    def device  = column[Uuid]("device")
    def fileEntity = column[Json]("fileEntity")

    def primKey = primaryKey("file_cache_pk", (role, version, device))

    override def * = (role, version, device, fileEntity) <>
      ((FileCache.apply _).tupled, FileCache.unapply)
  }
  protected [db] val fileCache = TableQuery[FileCacheTable]

  class FileCacheRequestTable(tag: Tag) extends Table[FileCacheRequest](tag, "FileCacheRequest") {
    def namespace = column[Namespace]("namespace")
    def version = column[Int]("version")
    def device = column[Uuid]("device")
    def status = column[FileCacheRequestStatus.Status]("status")

    def primKey = primaryKey("file_cache_request_pk", (version, device))

    override def * = (namespace, version, device, status) <>
      ((FileCacheRequest.apply _).tupled, FileCacheRequest.unapply)
  }
  protected [db] val fileCacheRequest = TableQuery[FileCacheRequestTable]
}
