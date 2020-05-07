package com.advancedtelematic.director.manifest

import java.time.Instant

import com.advancedtelematic.director.data.DeviceRequest.DeviceManifest
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuInstallationReport}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCompleted

object ManifestReportMessages {
  def apply(namespace: Namespace, deviceId: DeviceId,
            deviceManifest: DeviceManifest): Option[DeviceUpdateCompleted] = {

    val reportedImages = deviceManifest.ecu_version_manifests.mapValues { ecuManifest =>
      ecuManifest.signed.installed_image.filepath
    }

    deviceManifest.installation_report.map(_.report).map { report =>
      val ecuResults = report.items
        .filter(item => reportedImages.contains(item.ecu))
        .map { item =>
          item.ecu -> EcuInstallationReport(item.result, Seq(reportedImages(item.ecu).toString))
        }.toMap

      DeviceUpdateCompleted(namespace, Instant.now, report.correlation_id, deviceId, report.result, ecuResults, report.raw_report)
    }
  }
}
