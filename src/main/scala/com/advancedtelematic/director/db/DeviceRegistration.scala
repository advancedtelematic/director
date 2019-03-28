package com.advancedtelematic.director.db

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.AdminDataType.{EcuInfoImage, EcuInfoResponse, RegisterEcu}
import com.advancedtelematic.director.data.UptaneDataType.Hashes
import com.advancedtelematic.director.http.Errors
import com.advancedtelematic.director.repo.DeviceRoleGeneration
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._

class DeviceRegistration(keyserverClient: KeyserverClient)(implicit val db: Database, val ec: ExecutionContext) extends DeviceRepositorySupport
  with EcuRepositorySupport  {
  val roleGeneration = new DeviceRoleGeneration(keyserverClient)

  def findDeviceEcuInfo(ns: Namespace, deviceId: DeviceId): Future[Vector[EcuInfoResponse]] = async {
    val primary = await(ecuRepository.findDevicePrimary(ns, deviceId)).ecuSerial
    val ecus = await(ecuRepository.findTargets(ns, deviceId))

    ecus.map { case (ecu, target) =>
      val img = EcuInfoImage(target.filename, target.length, Hashes(target.checksum))
      EcuInfoResponse(ecu.ecuSerial, ecu.hardwareId, ecu.ecuSerial == primary, img)
    }.toVector
  }

  def register(ns: Namespace, repoId: RepoId, deviceId: DeviceId, primaryEcuId: EcuIdentifier, ecus: Seq[RegisterEcu]): Future[Unit] = {
    if (ecus.exists(_.ecu_serial == primaryEcuId)) {
      val _ecus = ecus.map(_.toEcu(ns, deviceId))

      for {
        _ <- deviceRepository.create(ns, deviceId, primaryEcuId, _ecus)
        _ <- roleGeneration.findFreshTargets(ns, repoId, deviceId)
      } yield ()
    } else
      FastFuture.failed(Errors.PrimaryIsNotListedForDevice)
  }
}
