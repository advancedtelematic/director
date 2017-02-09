package com.advancedtelematic.director.data

import java.time.Instant
import org.genivi.sota.data.Uuid

object DeviceRequest {
  import DataType.{EcuSerial, HexString, KeyId, Image, Signature}
  import SignatureMethod.SignatureMethod

  final case class ClientSignature(method: SignatureMethod, sig: HexString, keyid: KeyId) {
    def toSignature: Signature = Signature(method = method, sig = sig)
  }

  final case class SignedPayload[T](signatures: Seq[ClientSignature], signed: T)

  final case class EcuManifest(timeserver_time: Instant,
                               installed_image: Image,
                               previous_timeserver_time: Instant,
                               ecu_serial: EcuSerial,
                               attacks_detected: String)

  final case class DeviceManifest(vin: Uuid,
                                  primary_ecu_serial: EcuSerial,
                                  ecu_version_manifest: Seq[SignedPayload[EcuManifest]])
}
