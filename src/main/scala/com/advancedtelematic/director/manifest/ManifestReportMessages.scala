package com.advancedtelematic.director.manifest

import java.time.Instant

import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DbDataType.DeviceKnownState
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest, EcuManifestCustom}
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace, ResultCode, ResultDescription}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuInstallationReport, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCompleted

object ManifestReportMessages {

  private def fromInstallationReport(namespace: Namespace, beforeState: DeviceKnownState, deviceManifest: DeviceManifest): Option[DeviceUpdateCompleted] = {
    val reportedImages = deviceManifest.ecu_version_manifests.mapValues(_.signed.installed_image.filepath.value)

    val targetIdsByCorrelationIdAndEcuId =
      (beforeState.currentAssignments.map(a => (a.correlationId, a.ecuId) -> a.ecuTargetId) ++
        beforeState.processedAssignments.map(a => (a.correlationId, a.ecuId) -> a.ecuTargetId)).toMap

    def findTarget(correlationId: CorrelationId, ecuIdentifier: EcuIdentifier) =
      for {
        targetId   <- targetIdsByCorrelationIdAndEcuId.get((correlationId, ecuIdentifier))
        targetName <- beforeState.ecuTargets.get(targetId)
      } yield targetName.filename.value

    deviceManifest.installation_report.map(_.report).map { report =>
      val ecuResults = report.items
        .filter(item => reportedImages.contains(item.ecu))
        .map { item =>
          item.ecu -> EcuInstallationReport(item.result, findTarget(report.correlation_id, item.ecu).orElse(reportedImages.get(item.ecu)).toSeq)
        }.toMap

      DeviceUpdateCompleted(namespace, Instant.now, report.correlation_id, beforeState.deviceId, report.result, ecuResults, report.raw_report)
    }
  }

  // Some legacy devices do not send an installation report with correlation, so we need to extract the result from ecu reports
  private def fromEcuManifests(namespace: Namespace, deviceId: DeviceId, manifests: Map[CorrelationId, EcuManifest]): Set[DeviceUpdateCompleted] = {
    manifests.map { case (correlationId, ecuReport) =>
      val ecuReports = ecuReport.custom.flatMap(_.as[EcuManifestCustom].toOption).map { custom =>
        val operationResult = custom.operation_result
        val installationResult = InstallationResult(operationResult.isSuccess, ResultCode(operationResult.result_code.toString), ResultDescription(operationResult.result_text))
        Map(ecuReport.ecu_serial -> EcuInstallationReport(installationResult, Seq(ecuReport.installed_image.filepath.toString)))
      }.getOrElse(Map.empty)

      val installationResult = if (ecuReports.exists(!_._2.result.success)) {
        InstallationResult(success = false, ResultCode("19"), ResultDescription("One or more targeted ECUs failed to update"))
      } else {
        InstallationResult(success = true, ResultCode("0"), ResultDescription("All targeted ECUs were successfully updated"))
      }

      DeviceUpdateCompleted(namespace, Instant.now(), correlationId, deviceId, installationResult, ecuReports)
    }.toSet
  }

  def apply(namespace: Namespace, beforeState: DeviceKnownState, deviceManifest: DeviceManifest, ecuManifests: Map[CorrelationId, EcuManifest]): Set[DeviceUpdateCompleted] = {
    val default = fromInstallationReport(namespace, beforeState, deviceManifest)

    if(default.isDefined) {
      default.toSet
    } else {
      fromEcuManifests(namespace, beforeState.deviceId, ecuManifests)
    }
  }
}
