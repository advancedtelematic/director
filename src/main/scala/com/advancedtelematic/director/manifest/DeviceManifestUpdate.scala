package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, EcuManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, DeviceUpdateResult, UpdateTypesRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.data.TufDataType.{OperationResult, SignedPayload, TufKey}
import io.circe.Json
import org.slf4j.LoggerFactory

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(afterUpdate: AfterDeviceManifestUpdate,
                           verifier: TufKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceRepositorySupport
    with UpdateTypesRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[Json]): Future[Unit] = for {
    ecus <- deviceRepository.findEcus(namespace, device)
    ecuImages <- Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan))
    _ <- ecuManifests(namespace, device, ecuImages)
  } yield ()

  def ecuManifests(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest]): Future[Unit] = async {
    val updateResult = {
      val operations = deviceManifestOperationResults(ecuImages)
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
      case DeviceUpdateResult.NoChange => NoChange()
      case DeviceUpdateResult.UpdatedSuccessfully(nextVersion, None) => SuccessWithoutUpdateId()
      case DeviceUpdateResult.UpdatedSuccessfully(nextVersion, Some(updateId)) =>
        SuccessWithUpdateId(namespace, device, updateId, nextVersion, clientReport)
      case DeviceUpdateResult.UpdatedToWrongTarget(currentVersion, None, manifest) =>
        _log.error(s"Device ${device.show} updated when no update was available")
        _log.info {
          s"""currentVersion: $currentVersion
             |manifest      : $manifest
           """.stripMargin
        }
        Failed(namespace, device, currentVersion, None)
      case DeviceUpdateResult.UpdatedToWrongTarget(currentVersion, Some(targets), manifest) =>
        _log.error(s"Device ${device.show} updated to the wrong target")
        _log.info {
          s"""version : ${currentVersion + 1}
             |targets : $targets
             |manifest: $manifest
           """.stripMargin
        }
        Failed(namespace, device, currentVersion, None)
    }

  private def deviceManifestOperationResults(ecuManifests: Seq[EcuManifest]): Map[EcuSerial, OperationResult] =
    ecuManifests.par.flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption).map{ custom =>
        val op = custom.operation_result
        val image = ecuManifest.installed_image
        ecuManifest.ecu_serial -> OperationResult(image.filepath, image.fileinfo.hashes.toClientHashes, image.fileinfo.length,
                                                  op.result_code, op.result_text)
      }
    }.toMap.seq
}
