package com.advancedtelematic.director.data

import com.advancedtelematic.libtuf.data.ClientDataType.{ClientKey, ClientHashes => Hashes}

object AdminRequest {
  import DataType._

  final case class RegisterEcu(ecu_serial: EcuSerial, hardware_identifier: HardwareIdentifier, clientKey: ClientKey)

  final case class RegisterDevice(vin: DeviceId, primary_ecu_serial: EcuSerial, ecus: Seq[RegisterEcu])

  final case class SetTarget(updates: Map[EcuSerial, CustomImage])

  final case class FindAffectedRequest(filepath: String)

  final case class EcuInfoImage(filepath: String, size: Long, hash: Hashes)
  final case class EcuInfoResponse(id: EcuSerial, hardwareId: HardwareIdentifier, primary: Boolean, image: EcuInfoImage)
}
