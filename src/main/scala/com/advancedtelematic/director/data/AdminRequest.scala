package com.advancedtelematic.director.data

import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey

object AdminRequest {
  import DataType._

  final case class RegisterEcu(ecu_serial: EcuSerial, clientKey: ClientKey)

  final case class RegisterDevice(vin: DeviceId, primary_ecu_serial: EcuSerial, ecus: Seq[RegisterEcu])

  final case class SetTarget(updates: Map[EcuSerial, CustomImage])
}
