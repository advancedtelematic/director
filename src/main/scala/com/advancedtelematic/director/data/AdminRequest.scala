package com.advancedtelematic.director.data

import java.security.PublicKey

import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, TargetFilename, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, KeyType, TufKey}

object AdminRequest {
  import DataType._

  final case class RegisterEcu(ecu_serial: EcuSerial, hardware_identifier: Option[HardwareIdentifier], clientKey: TufKey) {
    import eu.timepit.refined.api.Refined
    // TODO: for backwards compatible reasons we allow the hardware_identifier to not be present in the request
    // and use the ecu_serial as the hardware_identifer
    lazy val hardwareId: HardwareIdentifier = hardware_identifier.getOrElse(Refined.unsafeApply(ecu_serial.value))
    def keyType: KeyType = clientKey.keytype
    def publicKey: PublicKey = clientKey.keyval
  }
  object RegisterEcu {
    def apply (ecu_serial: EcuSerial, hardware_identifier: HardwareIdentifier, clientKey: TufKey): RegisterEcu =
      RegisterEcu(ecu_serial, Some(hardware_identifier), clientKey)
  }

  final case class RegisterDevice(vin: DeviceId, primary_ecu_serial: EcuSerial, ecus: Seq[RegisterEcu])

  final case class SetTarget(updates: Map[EcuSerial, CustomImage])

  final case class FindAffectedRequest(filepath: TargetFilename)

  final case class EcuInfoImage(filepath: TargetFilename, size: Long, hash: Hashes)
  final case class EcuInfoResponse(id: EcuSerial, hardwareId: HardwareIdentifier, primary: Boolean, image: EcuInfoImage)

  final case class QueueResponse(updateId: Option[UpdateId], targets: Map[EcuSerial, CustomImage], inFlight: Boolean)

  final case class FindImageCount(filepaths: Seq[TargetFilename])
}
