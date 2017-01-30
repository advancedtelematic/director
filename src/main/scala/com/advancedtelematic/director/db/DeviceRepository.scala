package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.Ecu
import org.genivi.sota.data.{Namespace, Uuid}

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

trait DeviceRepositorySupport {
  def deviceRepository(implicit db: Database, ec: ExecutionContext) = new DeviceRepository()
}

protected class DeviceRepository()(implicit db: Database, ec: ExecutionContext) {
  import com.advancedtelematic.director.data.DataType.{EcuManifest, EcuSerial, InstalledImage, RegisterEcu}
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._

  private def byDevice(namespace: Namespace, device: Uuid): Query[Schema.EcuTable, Ecu, Seq] =
    Schema.ecu
      .filter(_.namespace === namespace)
      .filter(_.device === device)


  private def persistEcu(ecuManifest: EcuManifest): DBIO[Unit] = {
    Schema.currentImage.insertOrUpdate((ecuManifest.ecu_serial, ecuManifest.installed_image)).map(_ => ())
  }

  def persistAll(ecuManifests: Seq[EcuManifest]): Future[Unit] = {
    db.run(DBIO.seq(ecuManifests.map(persistEcu(_)) :_*).transactionally)
  }

  def findEcus(namespace: Namespace, device: Uuid): Future[Seq[Ecu]] =
    db.run(byDevice(namespace, device).result)

  def findImages(namespace: Namespace, device: Uuid): Future[Seq[(EcuSerial, InstalledImage)]] = db.run {
    byDevice(namespace, device)
      .map(_.ecuSerial)
      .join(Schema.currentImage).on(_ === _.id)
      .result
      .map(_.map{ case (ec, im) => (ec, im._2)})
  }

  def createDevice(namespace: Namespace, device: Uuid, primEcu: EcuSerial, ecus: Seq[RegisterEcu]): Future[Unit] = {
    val toClean = byDevice(namespace, device)
    val clean = Schema.currentImage.filter(_.id in toClean.map(_.ecuSerial)).delete.andThen(toClean.delete)

    def register(reg: RegisterEcu) = Schema.ecu += Ecu(reg.ecu_serial, device, namespace, reg.ecu_serial == primEcu, reg.crypto)

    val act = clean.andThen(DBIO.seq(ecus.map(register) :_*))

    db.run(act.transactionally)
  }
}
