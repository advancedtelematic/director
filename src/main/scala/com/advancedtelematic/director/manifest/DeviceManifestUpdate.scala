package com.advancedtelematic.director.manifest

import cats.syntax.either._
import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.DeviceId
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, DeviceManifest, EcuManifest, OperationResult}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, UpdateTypesRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import org.slf4j.LoggerFactory

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(afterUpdate: AfterDeviceManifestUpdate,
                           verifier: ClientKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceRepositorySupport
    with UpdateTypesRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[DeviceManifest]): Future[Unit] = async {
    val ecus = await(deviceRepository.findEcus(namespace, device))
    val ecuImages = await(Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan)))

    val updateResult = deviceManifestOperationResults(signedDevMan.signed) match {
      case Nil =>
        await(clientReportedNoErrors(namespace, device, ecuImages, None))
      case operations =>
        if (operations.forall(_.isSuccess)) {
          await(clientReportedNoErrors(namespace, device, ecuImages, Some(operations)))
        } else {
          _log.info(s"Device ${device.show} reports errors during install: $operations")
          val currentVersion = await(deviceRepository.getCurrentVersion(device))
          Failed(namespace, device, currentVersion, Some(operations))
        }
    }
    await(afterUpdate.report(updateResult))
  }

  private def clientReportedNoErrors(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest],
                                     clientReport: Option[Seq[OperationResult]]): Future[DeviceManifestUpdateResult] =
    DeviceUpdate.checkAgainstTarget(namespace, device, ecuImages).map {
      case None => SuccessWithoutUpdateId()
      case Some((nextVersion, updateId)) => SuccessWithUpdateId(namespace, device, updateId, nextVersion, clientReport)
    }.recover {
      case DeviceUpdate.DeviceUpdatedToWrongTarget(currentVersion) =>
        _log.info(s"Device ${device.show} updated to the wrong version, cancel remaining targets")
        Failed(namespace, device, currentVersion, None)
    }

  private def deviceManifestOperationResults(deviceManifest: DeviceManifest): Seq[OperationResult] = {
    deviceManifest.ecu_version_manifest.flatMap(_.signed.custom.flatMap(_.as[CustomManifest].toOption))
      .map(_.operation_result)
  }
}
