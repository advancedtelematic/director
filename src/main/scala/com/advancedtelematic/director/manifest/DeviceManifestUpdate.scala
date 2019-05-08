package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, DeviceManifest, EcuManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, DeviceUpdateResult}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace, ResultCode, ResultDescription}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuInstallationReport, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCompleted
import com.advancedtelematic.libtuf.data.TufDataType.{SignedPayload, TufKey}
import io.circe.Json
import org.slf4j.LoggerFactory
import java.time.Instant

import com.advancedtelematic.libats.data.EcuIdentifier

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(afterUpdate: AfterDeviceManifestUpdate,
                           verifier: TufKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private val successInstallationResult = InstallationResult(true, ResultCode("0"), ResultDescription("All targeted ECUs were successfully updated"))
  private val failureInstallationResult = InstallationResult(false, ResultCode("19"), ResultDescription("One or more targeted ECUs failed to update"))
  private val unexpectedTargetResult = InstallationResult(false, ResultCode("20"), ResultDescription("Device reported incorrect filepath, hash, or length of ECU targets"))


  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[Json]): Future[Unit] = for {
    ecus <- deviceRepository.findEcus(namespace, device)
    deviceManifest <- Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan))
    _ <- processManifest(namespace, device, deviceManifest)
  } yield ()

  private def processManifest(namespace: Namespace, device: DeviceId, deviceManifest: DeviceManifest): Future[Unit] = {

    DeviceUpdate.checkAgainstTarget(namespace, device, deviceManifest.ecu_manifests).flatMap {
      case DeviceUpdateResult.NoUpdate => Future.successful(())
      case DeviceUpdateResult.UpdateNotCompleted(updateAssignment) =>
        _log.debug(s"UpdateNotCompleted $updateAssignment")
        val f = for {
          correlationId <- updateAssignment.correlationId
          ireport <- toDeviceInstallationReport(namespace, device, deviceManifest, correlationId)
          report = ireport if !ireport.result.success
        } yield afterUpdate.clearUpdate(report)
        f.getOrElse(Future.successful(()))
      case DeviceUpdateResult.UpdateSuccessful(updateAssignment) =>
        _log.debug(s"UpdateSuccessful $updateAssignment")
        val f = for {
          correlationId <- updateAssignment.correlationId
          report <- toDeviceInstallationReport(namespace, device, deviceManifest, correlationId, enforceExplicit = false)
        } yield afterUpdate.clearUpdate(report)
        f.getOrElse(Future.successful(()))
      case DeviceUpdateResult.UpdateUnexpectedTarget(updateAssignment, targets, manifest) =>
        _log.debug(s"UpdateUnexpectedTarget $updateAssignment")
        val currentVersion = updateAssignment.version - 1
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
          correlationId <- updateAssignment.correlationId
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

  private def toEcuInstallationReports(ecuManifests: Seq[EcuManifest]): Map[EcuIdentifier, EcuInstallationReport] =
    ecuManifests.par.flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption).map { custom =>
        val op = custom.operation_result
        ecuManifest.ecu_serial -> EcuInstallationReport(
          InstallationResult(op.result_code == 0, ResultCode(op.result_code.toString), ResultDescription(op.result_text)),
          Seq(ecuManifest.installed_image.filepath.toString))
      }
    }.toMap.seq

  private def toDeviceInstallationReport(
    namespace: Namespace, deviceId: DeviceId, deviceManifest: DeviceManifest,
    correlationId: CorrelationId, enforceExplicit: Boolean = true
  ): Option[DeviceUpdateCompleted] = {
    val ecuImages = deviceManifest.ecu_manifests.map { ecuManifest =>
      ecuManifest.ecu_serial -> ecuManifest.installed_image.filepath
    }.toMap

    deviceManifest.installation_report.map { ireport =>
      val report = ireport.report
      val ecuResults = report.items
        .filter(item => ecuImages.contains(item.ecu))
        .map { item =>
          item.ecu -> EcuInstallationReport(item.result, Seq(ecuImages(item.ecu).toString))
        }.toMap

      val result = DeviceUpdateCompleted(
        namespace, Instant.now, report.correlation_id, deviceId, report.result, ecuResults, report.raw_report)
      _log.debug(s"New DeviceUpdateCompleted ${result}")
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
        val result = Some(DeviceUpdateCompleted(
          namespace, Instant.now, correlationId, deviceId, installationResult, ecuResults))
        _log.debug(s"Old DeviceUpdateCompleted ${result}")
        result
      }
    }
  }
}
