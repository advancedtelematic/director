package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.DataType.CorrelationId
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuSerial, UpdateId}
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

  final case class DeviceManifest(primary_ecu_serial: EcuSerial,
                                  ecu_version_manifests: Map[EcuSerial, Json])

  final case class DeviceRegistration(primary_ecu_serial: EcuSerial,
                                      ecus: Seq[AdminRequest.RegisterEcu])

  @deprecated("Use InstallationReport", "")
  final case class OperationResult(id: String, result_code: Int, result_text: String) {
    def isSuccess: Boolean = result_code == 0 || result_code == 1
    def isFail: Boolean = !isSuccess
  }

  final case class InstallationReport(
    correlationId: CorrelationId,
    resultCode: Int,
    resultText: String,
    data: Option[String])

  object InstallationReport {
    def from(updateId: UpdateId, op: OperationResult) = InstallationReport(
      CorrelationId.from(updateId),
      op.result_code,
      op.result_text,
      None)
  }

  final case class CustomManifest(
    operation_result: Option[OperationResult],
    installationReport: Option[InstallationReport])

}
