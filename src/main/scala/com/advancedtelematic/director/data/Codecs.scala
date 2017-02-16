package com.advancedtelematic.director.data

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

import org.genivi.sota.marshalling.CirceInstances.{refinedMapEncoder => _, refinedMapDecoder => _, _}
import org.genivi.sota.marshalling.CirceInstances.{refinedDecoder, refinedEncoder}
import org.genivi.sota.marshalling.CirceInstances.{dateTimeDecoder, dateTimeEncoder}

import scala.util.Try

object Codecs {
  import AdminRequest._
  import DataType._
  import DeviceRequest._
  import HashMethod._
  import RefinedUtils._
  import io.circe.generic.semiauto._

  implicit val decoderCrypto: Decoder[Crypto] = deriveDecoder
  implicit val encoderCrypto: Encoder[Crypto] = deriveEncoder

  implicit val keyDecoderHashMethodKey: KeyDecoder[HashMethod] = KeyDecoder.instance { value =>
    Try(HashMethod.withName(value)).toOption
  }
  implicit val keyEncoderHashMethodKey: KeyEncoder[HashMethod] = KeyEncoder[String].contramap(_.toString)

  implicit val keyDecoderEcuSerial: KeyDecoder[EcuSerial] = KeyDecoder.instance { value =>
    value.refineTry[ValidEcuSerial].toOption
  }
  implicit val keyEncoderEcuSerial: KeyEncoder[EcuSerial] = KeyEncoder[String].contramap(_.get)

  implicit val decoderFileInfo: Decoder[FileInfo] = deriveDecoder
  implicit val encoderFileInfo: Encoder[FileInfo] = deriveEncoder

  implicit val decoderImage: Decoder[Image] = deriveDecoder
  implicit val encoderImage: Encoder[Image] = deriveEncoder

  /*** Device Request ***/

  implicit val decoderClientSignature: Decoder[ClientSignature] = deriveDecoder
  implicit val encoderClientSignature: Encoder[ClientSignature] = deriveEncoder

  implicit def decoderSignedPayload[T](implicit decoderT: Decoder[T]): Decoder[SignedPayload[T]] = deriveDecoder
  implicit def encoderSignedPayload[T](implicit encoderT: Encoder[T]): Encoder[SignedPayload[T]] = deriveEncoder

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
