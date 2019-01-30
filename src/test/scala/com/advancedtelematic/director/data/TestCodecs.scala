package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.DeviceManifestEcuSigned
import com.advancedtelematic.director.data.Legacy.LegacyDeviceManifest
import com.advancedtelematic.libats.codecs.CirceValidatedGeneric
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libtuf.data.TufCodecs._
import io.circe.{Decoder, Encoder, KeyEncoder}
import io.circe.generic.semiauto._

object TestCodecs {

  implicit val ecuIdentifierKeyEncoder: KeyEncoder[EcuIdentifier] = CirceValidatedGeneric.validatedGenericKeyEncoder

  implicit val encoderDeviceManifestEcuSigned: Encoder[DeviceManifestEcuSigned] = deriveEncoder

  implicit val encoderLegacyDeviceManifest: Encoder[LegacyDeviceManifest] = deriveEncoder
  implicit val decoderLegacyDeviceManifest: Decoder[LegacyDeviceManifest] = deriveDecoder

}
