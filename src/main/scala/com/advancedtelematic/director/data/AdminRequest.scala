package com.advancedtelematic.director.data

import org.genivi.sota.data.Uuid

object AdminRequest {
  import DataType._

  final case class RegisterEcu(ecu_serial: EcuSerial, crypto: Crypto)

  final case class RegisterDevice(vin: Uuid, primary_ecu_serial: EcuSerial, ecus: Seq[RegisterEcu])
}
