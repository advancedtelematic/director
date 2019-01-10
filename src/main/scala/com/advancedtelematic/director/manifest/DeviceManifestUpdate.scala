package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, DeviceManifest, EcuManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, DeviceUpdateResult}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.{
  DeviceId, EcuSerial, EcuInstallationReport, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.libtuf.data.TufDataType.{SignedPayload, TufKey}
import io.circe.Json
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(afterUpdate: AfterDeviceManifestUpdate,
                           verifier: TufKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private val successInstallationResult = InstallationResult(true, "0", "All targeted ECUs were successfully updated")
  private val failureInstallationResult = InstallationResult(false, "19", "One or more targeted ECUs failed to update")
  private val unexpectedTargetResult = InstallationResult(false, "20", "Device reported incorrect filepath, hash, or length of ECU targets")


  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[Json]): Future[Unit] = for {
    ecus <- deviceRepository.findEcus(namespace, device)
    deviceManifest <- Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan))
    _ <- processManifest(namespace, device, deviceManifest)
  } yield ()

  private def processManifest(namespace: Namespace, device: DeviceId, deviceManifest: DeviceManifest): Future[Unit] = {

    DeviceUpdate.checkAgainstTarget(namespace, device, deviceManifest.ecu_manifests).flatMap {
      case DeviceUpdateResult.NoUpdate => Future.successful(())
      case DeviceUpdateResult.UpdateNotCompleted(updateTarget) =>
        _log.debug(s"UpdateNotCompleted $updateTarget")
        val f = for {
          correlationId <- updateTarget.correlationId
          ireport <- toDeviceInstallationReport(namespace, device, deviceManifest, correlationId)
          report = ireport if (!ireport.result.success)
        } yield afterUpdate.clearUpdate(report)
        f.getOrElse(Future.successful(()))
      case DeviceUpdateResult.UpdateSuccessful(updateTarget) =>
        _log.debug(s"UpdateSuccessful $updateTarget")
        val f = for {
          correlationId <- updateTarget.correlationId
          report <- toDeviceInstallationReport(namespace, device, deviceManifest, correlationId, enforceExplicit = false)
        } yield afterUpdate.clearUpdate(report)
        f.getOrElse(Future.successful(()))
      case DeviceUpdateResult.UpdateUnexpectedTarget(updateTarget, targets, manifest) =>
        _log.debug(s"UpdateUnexpectedTarget $updateTarget")
        val currentVersion = updateTarget.targetVersion - 1
        if (targets.isEmpty) {
          _log.debug(s"Device ${device.show} updated when no update was available")
        } else {
          _log.debug(s"Device ${device.show} updated to the wrong target")
        }
        _log.info {
          s"""version : ${currentVersion + 1}
             |targets : $targets
             |manifest: $manifest
           """.stripMargin
        }
        val f = for {
          correlationId <- updateTarget.correlationId
          report <- toDeviceInstallationReport(namespace, device, deviceManifest, correlationId, enforceExplicit = false)
        } yield {
          val reportUpdated = if (report.result.success)
            report.copy(result = unexpectedTargetResult)
          else
            report
          afterUpdate.clearUpdate(reportUpdated)
        }
        f.getOrElse(Future.successful(()))
    }
  }

  private def toEcuInstallationReports(ecuManifests: Seq[EcuManifest]): Map[EcuSerial, EcuInstallationReport] =
    ecuManifests.par.flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption).map { custom =>
        val op = custom.operation_result
        ecuManifest.ecu_serial -> EcuInstallationReport(
          InstallationResult(op.result_code == 0, op.result_code.toString, op.result_text),
          Seq(ecuManifest.installed_image.filepath.toString),
          None)
      }
    }.toMap.seq

  private def toDeviceInstallationReport(
    namespace: Namespace, deviceId: DeviceId, deviceManifest: DeviceManifest,
    correlationId: CorrelationId, enforceExplicit: Boolean = true
  ): Option[DeviceInstallationReport] = {
    val ecuImages = deviceManifest.ecu_manifests.map { ecuManifest =>
      ecuManifest.ecu_serial -> ecuManifest.installed_image.filepath
    }.toMap

    deviceManifest.installation_report.map { ireport =>
      val report = ireport.report
      val ecuResults = report.items
        .filter(item => ecuImages.contains(item.ecu))
        .map { item =>
          item.ecu -> EcuInstallationReport(item.result, Seq(ecuImages.get(item.ecu).get.toString), None)
        }.toMap

      val result = DeviceInstallationReport(
        namespace, deviceId, report.correlation_id, report.result, ecuResults, None, Instant.now)
      _log.debug(s"New DeviceInstallationReport ${result}")
      result

    }.orElse {

      // Support legacy `custom.operation_result`
      val ecuResults = toEcuInstallationReports(deviceManifest.ecu_manifests)
      val installationResult = if (ecuResults.exists(!_._2.result.success)) {
        failureInstallationResult
      } else {
        successInstallationResult
      }

      if (ecuResults.isEmpty && enforceExplicit) {
        None
      } else {
        val result = Some(DeviceInstallationReport(
          namespace, deviceId, correlationId, installationResult, ecuResults, None, Instant.now))
        _log.debug(s"Old DeviceInstallationReport ${result}")
        result
      }
    }
  }
}
