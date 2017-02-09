package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.SignatureMethod._
import org.genivi.sota.data.{Namespace, Uuid}
import slick.driver.MySQLDriver.api._

object Schema {
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._

  type EcuRow = (EcuSerial, Uuid, Namespace, Boolean, SignatureMethod, String)
  class EcuTable(tag: Tag) extends Table[Ecu](tag, "Ecu") {
    def ecuSerial = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def device = column[Uuid]("device")
    def namespace = column[Namespace]("namespace")
    def primary = column[Boolean]("primary")
    def cryptoMethod = column[SignatureMethod]("cryptographic_method")
    def publicKey = column[String]("public_key")

    override def * = (ecuSerial, device, namespace, primary, cryptoMethod, publicKey) <>
      ((x: EcuRow) => Ecu(x._1, x._2, x._3, x._4, Crypto(x._5, x._6)),
       (x: Ecu) => Some((x.ecuSerial, x.device, x.namespace, x.primary, x.crypto.method, x.crypto.publicKey))
       )
  }

  protected [db] val ecu = TableQuery[EcuTable]

  type CurrentImageRow = (EcuSerial, String, Int, Sha256, Sha512)
  class CurrentImageTable(tag: Tag) extends Table[(EcuSerial, Image)](tag, "CurrentImage") {
    def id = column[EcuSerial]("ecu_serial", O.PrimaryKey)
    def filepath = column[String]("filepath")
    def length = column[Int]("length")
    def sha256 = column[Sha256]("sha256")
    def sha512 = column[Sha512]("sha512")

    def ecuFK = foreignKey("ECU_FK", id, ecu)(_.ecuSerial)

    override def * = (id, filepath, length, sha256, sha512) <>
      ((p: CurrentImageRow) => (p._1, Image(p._2, FileInfo(Hashes(p._4, p._5), p._3))),
      (x: (EcuSerial, Image)) => Some((x._1, x._2.filepath, x._2.fileinfo.length, x._2.fileinfo.hashes.sha256, x._2.fileinfo.hashes.sha512)))
  }

  protected [db] val currentImage = TableQuery[CurrentImageTable]
}
