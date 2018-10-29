package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, EcuManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, DeviceUpdateResult}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.data.TufDataType.{OperationResult, SignedPayload, TufKey}
import io.circe.Json
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(afterUpdate: AfterDeviceManifestUpdate,
                           verifier: TufKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[Json]): Future[Unit] = for {
    ecus <- deviceRepository.findEcus(namespace, device)
    ecuImages <- Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan))
    _ <- ecuManifests(namespace, device, ecuImages)
  } yield ()

  private def ecuManifests(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest]): Future[Unit] = {

    val operations = deviceManifestOperationResults(ecuImages)
    DeviceUpdate.checkAgainstTarget(namespace, device, ecuImages).flatMap {
      case DeviceUpdateResult.NoChange =>
        if (operations.find(!_._2.isSuccess).isDefined) {
          _log.info(s"Device ${device.show} reports errors during install: $operations")
          deviceRepository.getCurrentVersion(device).flatMap { currentVersion =>
            afterUpdate.failedMultiTargetUpdate(namespace, device, currentVersion, operations)
          }
        } else {
          Future.successful(())
        }
      case DeviceUpdateResult.UpdatedSuccessfully(nextVersion, None) => Future.successful(())
      case DeviceUpdateResult.UpdatedSuccessfully(nextVersion, Some(correlationId)) =>
        afterUpdate.successMultiTargetUpdate(namespace, device, correlationId, nextVersion, operations)
      case DeviceUpdateResult.UpdatedToWrongTarget(currentVersion, targets, manifest) =>
        if (targets.isEmpty) {
          _log.error(s"Device ${device.show} updated when no update was available")
        } else {
          _log.error(s"Device ${device.show} updated to the wrong target")
        }
        _log.info {
          s"""version : ${currentVersion + 1}
             |targets : $targets
             |manifest: $manifest
           """.stripMargin
        }
        afterUpdate.failedMultiTargetUpdate(namespace, device, currentVersion, operations)
    }

  }

  private def deviceManifestOperationResults(ecuManifests: Seq[EcuManifest]): Map[EcuSerial, OperationResult] =
    ecuManifests.par.flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption).flatMap(_.operation_result).map { op =>
        val image = ecuManifest.installed_image
        ecuManifest.ecu_serial -> OperationResult(image.filepath, image.fileinfo.hashes.toClientHashes, image.fileinfo.length,
                                                  op.result_code, op.result_text)
      }
    }.toMap.seq
}
