package com.advancedtelematic.director.data

import java.security.PublicKey

import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, KeyType, TargetFilename, TufKey}

object AdminRequest {
  import DataType._

  final case class RegisterEcu(ecu_serial: EcuIdentifier, hardware_identifier: Option[HardwareIdentifier], clientKey: TufKey) {
    import eu.timepit.refined.api.Refined
    // TODO: for backwards compatible reasons we allow the hardware_identifier to not be present in the request
    // and use the ecu_serial as the hardware_identifer
    lazy val hardwareId: HardwareIdentifier = hardware_identifier.getOrElse(Refined.unsafeApply(ecu_serial.value))
    def keyType: KeyType = clientKey.keytype
    def publicKey: PublicKey = clientKey.keyval
  }
  object RegisterEcu {
    def apply (ecu_serial: EcuIdentifier, hardware_identifier: HardwareIdentifier, clientKey: TufKey): RegisterEcu =
      RegisterEcu(ecu_serial, Some(hardware_identifier), clientKey)
  }

  final case class RegisterDevice(vin: DeviceId, primary_ecu_serial: EcuIdentifier, ecus: Seq[RegisterEcu])

  final case class SetTarget(updates: Map[EcuIdentifier, CustomImage])

  final case class FindAffectedRequest(filepath: TargetFilename)

  final case class EcuInfoImage(filepath: TargetFilename, size: Long, hash: Hashes)
  final case class EcuInfoResponse(id: EcuIdentifier, hardwareId: HardwareIdentifier, primary: Boolean, image: EcuInfoImage)

  final case class AssignUpdateRequest(
    correlationId: CorrelationId,
    devices: Seq[DeviceId],
    mtuId: UpdateId,
    dryRun: Option[Boolean] = Some(false))

  final case class QueueResponse(correlationId: Option[CorrelationId], targets: Map[EcuIdentifier, CustomImage], inFlight: Boolean)

  final case class FindImageCount(filepaths: Seq[TargetFilename])
}
