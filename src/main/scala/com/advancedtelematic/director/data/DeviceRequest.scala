package com.advancedtelematic.director.data

import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload

import java.time.Instant
import org.genivi.sota.data.Uuid

object DeviceRequest {
  import DataType.{EcuSerial, Image}

  final case class EcuManifest(timeserver_time: Instant,
                               installed_image: Image,
                               previous_timeserver_time: Instant,
                               ecu_serial: EcuSerial,
                               attacks_detected: String) // why is this string?

  final case class DeviceManifest(vin: Uuid,
                                  primary_ecu_serial: EcuSerial,
                                  ecu_version_manifest: Seq[SignedPayload[EcuManifest]])
}
