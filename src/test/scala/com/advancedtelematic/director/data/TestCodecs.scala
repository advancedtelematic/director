package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.DeviceManifestEcuSigned
import com.advancedtelematic.director.data.Legacy.LegacyDeviceManifest
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

object TestCodecs {

  implicit val encoderDeviceManifestEcuSigned: Encoder[DeviceManifestEcuSigned] = deriveEncoder

  implicit val encoderLegacyDeviceManifest: Encoder[LegacyDeviceManifest] = deriveEncoder
  implicit val decoderLegacyDeviceManifest: Decoder[LegacyDeviceManifest] = deriveDecoder

}
