package com.advancedtelematic.director.data

import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuSerial, InstallationResult}
import io.circe.Json

import java.time.Instant

object DeviceRequest {
  import DataType.Image

  final case class EcuManifest(timeserver_time: Instant,
                               installed_image: Image,
                               previous_timeserver_time: Instant,
                               ecu_serial: EcuSerial,
                               attacks_detected: String,
                               custom: Option[Json] = None)

  final case class DeviceManifestEcuSigned(primary_ecu_serial: EcuSerial,
                                  ecu_version_manifests: Map[EcuSerial, Json],
                                  installation_report: Option[InstallationReportEntity] = None)

  final case class DeviceManifest(primary_ecu_serial: EcuSerial,
                                  ecu_manifests: Seq[EcuManifest],
                                  installation_report: Option[InstallationReportEntity] = None)

  final case class DeviceRegistration(primary_ecu_serial: EcuSerial,
                                      ecus: Seq[AdminRequest.RegisterEcu])

  final case class OperationResult(id: String, result_code: Int, result_text: String) {
    def isSuccess: Boolean = result_code == 0 || result_code == 1
    def isFail: Boolean = !isSuccess
  }

  final case class CustomManifest(operation_result: OperationResult)

  final case class InstallationReportEntity(content_type: String, report: InstallationReport)

  final case class InstallationReport(
    correlation_id: CorrelationId,
    result: InstallationResult,
    items: Seq[InstallationItem],
    raw_report: Option[String])

  final case class InstallationItem(ecu: EcuSerial, result: InstallationResult)
}
