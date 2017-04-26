package com.advancedtelematic.director.data

import com.advancedtelematic.libtuf.data.ClientDataType.{ClientKey, TargetFilename, ClientHashes => Hashes}

object AdminRequest {
  import DataType._

  final case class RegisterEcu(ecu_serial: EcuSerial, hardware_identifier: Option[HardwareIdentifier], clientKey: ClientKey) {
    import eu.timepit.refined.api.Refined
    // TODO: for backwards compatible reasons we allow the hardware_identifier to not be present in the request
    // and use the ecu_serial as the hardware_identifer
    lazy val hardwareId: HardwareIdentifier = hardware_identifier.getOrElse(Refined.unsafeApply(ecu_serial.value))
  }
  object RegisterEcu {
    def apply (ecu_serial: EcuSerial, hardware_identifier: HardwareIdentifier, clientKey: ClientKey): RegisterEcu =
      RegisterEcu(ecu_serial, Some(hardware_identifier), clientKey)
  }

  final case class RegisterDevice(vin: DeviceId, primary_ecu_serial: EcuSerial, ecus: Seq[RegisterEcu])

  final case class SetTarget(updates: Map[EcuSerial, CustomImage])

  final case class FindAffectedRequest(filepath: TargetFilename)

  final case class EcuInfoImage(filepath: TargetFilename, size: Long, hash: Hashes)
  final case class EcuInfoResponse(id: EcuSerial, hardwareId: HardwareIdentifier, primary: Boolean, image: EcuInfoImage)
}
