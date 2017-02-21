package com.advancedtelematic.director.data

import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

import com.advancedtelematic.libats.codecs.AkkaCirce._

object Codecs {
  import AdminRequest._
  import DataType._
  import DeviceRequest._
  import RefinedUtils._
  import io.circe.generic.semiauto._

  implicit val keyDecoderEcuSerial: KeyDecoder[EcuSerial] = KeyDecoder.instance { value =>
    value.refineTry[ValidEcuSerial].toOption
  }
  implicit val keyEncoderEcuSerial: KeyEncoder[EcuSerial] = KeyEncoder[String].contramap(_.get)

  implicit val decoderFileInfo: Decoder[FileInfo] = deriveDecoder
  implicit val encoderFileInfo: Encoder[FileInfo] = deriveEncoder

  implicit val decoderImage: Decoder[Image] = deriveDecoder
  implicit val encoderImage: Encoder[Image] = deriveEncoder

  /*** Device Request ***/

  implicit val decoderEcuManifest: Decoder[EcuManifest] = deriveDecoder
  implicit val encoderEcuManifest: Encoder[EcuManifest] = deriveEncoder

  implicit val decoderDeviceManifest: Decoder[DeviceManifest] = deriveDecoder
  implicit val encoderDeviceManifest: Encoder[DeviceManifest] = deriveEncoder

  /*** Admin Request ***/
  implicit val decoderRegisterEcu: Decoder[RegisterEcu] = deriveDecoder
  implicit val encoderRegisterEcu: Encoder[RegisterEcu] = deriveEncoder

  implicit val decoderRegisterDevice: Decoder[RegisterDevice] = deriveDecoder
  implicit val encoderRegisterDevice: Encoder[RegisterDevice] = deriveEncoder

  implicit val decoderSetTarget: Decoder[SetTarget] = deriveDecoder
  implicit val encoderSetTarget: Encoder[SetTarget] = deriveEncoder
}
