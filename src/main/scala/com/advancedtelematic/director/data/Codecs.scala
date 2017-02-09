package com.advancedtelematic.director.data

import io.circe._
import io.circe.generic.semiauto._

import org.genivi.sota.marshalling.CirceInstances._

object Codecs {
  import DataType._
  import AdminRequest._
  import DeviceRequest._

  implicit val decoderCrypto: Decoder[Crypto] = deriveDecoder
  implicit val encoderCrypto: Encoder[Crypto] = deriveEncoder

  implicit val decoderHashes: Decoder[Hashes] = deriveDecoder
  implicit val encoderHashes: Encoder[Hashes] = deriveEncoder

  implicit val decoderFileInfo: Decoder[FileInfo] = deriveDecoder
  implicit val encoderFileInfo: Encoder[FileInfo] = deriveEncoder

  implicit val decoderImage: Decoder[Image] = deriveDecoder
  implicit val encoderImage: Encoder[Image] = deriveEncoder

  /*** Device Request ***/

  implicit val decoderClientSignature: Decoder[ClientSignature] = deriveDecoder
  implicit val encoderClientSignature: Encoder[ClientSignature] = deriveEncoder

  implicit def decoderSignedPayload[T](implicit decoderT: Decoder[T]): Decoder[SignedPayload[T]] = deriveDecoder
  implicit def encoderSignedPayload[T](implicit decoderT: Encoder[T]): Encoder[SignedPayload[T]] = deriveEncoder

  implicit val decoderEcuManifest: Decoder[EcuManifest] = deriveDecoder
  implicit val encoderEcuManifest: Encoder[EcuManifest] = deriveEncoder

  implicit val decoderDeviceManifest: Decoder[DeviceManifest] = deriveDecoder
  implicit val encoderDeviceManifest: Encoder[DeviceManifest] = deriveEncoder

  /*** Admin Request ***/
  implicit val decoderRegisterEcu: Decoder[RegisterEcu] = deriveDecoder
  implicit val encoderRegisterEcu: Encoder[RegisterEcu] = deriveEncoder

  implicit val decoderRegisterDevice: Decoder[RegisterDevice] = deriveDecoder
  implicit val encoderRegisterDevice: Encoder[RegisterDevice] = deriveEncoder
}
