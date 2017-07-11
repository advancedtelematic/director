package com.advancedtelematic.director.manifest

import cats.syntax.either._
import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, DeviceManifest, EcuManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, DeviceUpdateResult, UpdateTypesRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, OperationResult}
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

    val updateResult = {
      val operations = deviceManifestOperationResults(signedDevMan.signed)
      if (operations.isEmpty) {
        await(clientReportedNoErrors(namespace, device, ecuImages, None))
      } else if (operations.forall(_._2.isSuccess)) {
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
                                     clientReport: Option[Map[EcuSerial, OperationResult]]): Future[DeviceManifestUpdateResult] =
    DeviceUpdate.checkAgainstTarget(namespace, device, ecuImages).map {
      case DeviceUpdateResult.NoChange() => NoChange()
      case DeviceUpdateResult.UpdatedSuccessfully(nextVersion, None) => SuccessWithoutUpdateId()
      case DeviceUpdateResult.UpdatedSuccessfully(nextVersion, Some(updateId)) =>
        SuccessWithUpdateId(namespace, device, updateId, nextVersion, clientReport)
      case DeviceUpdateResult.UpdatedToWrongTarget(currentVersion, targets, manifest) =>
        _log.error(s"Device ${device.show} updated to the wrong target")
        _log.info {
          s"""version : ${currentVersion + 1}
             |targets : $targets
             |manifest: $manifest
           """.stripMargin
        }
        Failed(namespace, device, currentVersion, None)
    }

  private def deviceManifestOperationResults(deviceManifest: DeviceManifest): Map[EcuSerial, OperationResult] =
    deviceManifest.ecu_version_manifest.par.map(_.signed).flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption).map{ custom =>
        val op = custom.operation_result
        val image = ecuManifest.installed_image
        ecuManifest.ecu_serial -> OperationResult(image.filepath, image.fileinfo.hashes, image.fileinfo.length,
                                                  op.result_code, op.result_text)
      }
    }.toMap.seq
}
