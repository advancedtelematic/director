package com.advancedtelematic.director.data

import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.http.HttpCodecs._
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuSerial, ValidEcuSerial}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, JsonObject}

object Codecs {
  import AdminRequest._
  import DeviceRequest._
  import io.circe.generic.semiauto._

  implicit val decoderFileInfo: Decoder[FileInfo] = deriveDecoder
  implicit val encoderFileInfo: Encoder[FileInfo] = deriveEncoder

  implicit val decoderDiffInfo: Decoder[DiffInfo] = deriveDecoder
  implicit val encoderDiffInfo: Encoder[DiffInfo] = deriveEncoder

  implicit val decoderHashes: Decoder[Hashes] = deriveDecoder
  implicit val encoderHashes: Encoder[Hashes] = deriveEncoder

  implicit val decoderImage: Decoder[Image] = deriveDecoder
  implicit val encoderImage: Encoder[Image] = deriveEncoder

  implicit val decoderTargetCustom: Decoder[TargetCustom] = deriveDecoder
  implicit val encoderTargetCustom: Encoder[TargetCustom] = deriveEncoder

  implicit val decoderCustomImage: Decoder[CustomImage] = deriveDecoder
  implicit val encoderCustomImage: Encoder[CustomImage] = deriveEncoder

  /*** Device Request ***/

  implicit val decoderEcuManifest: Decoder[EcuManifest] = deriveDecoder
  implicit val encoderEcuManifest: Encoder[EcuManifest] = deriveEncoder[EcuManifest].mapJsonObject{ obj =>
    JsonObject.fromMap(obj.toMap.filter{
                         case ("custom", value) => !value.isNull
                         case _ => true
                       })
  }

  implicit val decoderDeviceManifest: Decoder[DeviceManifest] = Decoder.instance { cursor =>
    cursor.downField("primary_ecu_serial").as[EcuSerial].flatMap { primEcu =>
      cursor.downField("ecu_version_manifests").as[Option[Map[EcuSerial, Json]]].flatMap {
        case Some(map) => Right(DeviceManifest(primEcu, map))
        // the legacy format
        case None => cursor.downField("ecu_version_manifest").as[Seq[Json]].flatMap { signedEcus =>
          signedEcus.toList.traverseU(sEcu => sEcu.hcursor.downField("signed").downField("ecu_serial").as[EcuSerial].map(_ -> sEcu))
            .map(ecus => DeviceManifest(primEcu, ecus.toMap))
        }
      }
    }
  }

  implicit val decoderDeviceRegistration: Decoder[DeviceRegistration] = deriveDecoder
  implicit val encoderDeviceRegistration: Encoder[DeviceRegistration] = deriveEncoder

  implicit val decoderOperationResult: Decoder[OperationResult] = deriveDecoder
  implicit val encoderOperationResult: Encoder[OperationResult] = deriveEncoder

  implicit val decoderCustomManifest: Decoder[CustomManifest] = deriveDecoder
  implicit val encoderCustomManifest: Encoder[CustomManifest] = deriveEncoder

  /*** Admin Request ***/
  implicit val decoderRegisterEcu: Decoder[RegisterEcu] = deriveDecoder
  implicit val encoderRegisterEcu: Encoder[RegisterEcu] = deriveEncoder[RegisterEcu].mapJsonObject{ obj =>
    JsonObject.fromMap(obj.toMap.filter{
                         case ("hardware_identifier", value) => !value.isNull
                         case _ => true
                       })
  }

  implicit val decoderRegisterDevice: Decoder[RegisterDevice] = deriveDecoder
  implicit val encoderRegisterDevice: Encoder[RegisterDevice] = deriveEncoder

  implicit val decoderSetTarget: Decoder[SetTarget] = deriveDecoder
  implicit val encoderSetTarget: Encoder[SetTarget] = deriveEncoder

  implicit val decoderTargetUpdate: Decoder[TargetUpdate] = deriveDecoder[TargetUpdate] or decoderImage.map(_.toTargetUpdate)
  implicit val encoderTargetUpdate: Encoder[TargetUpdate] = deriveEncoder

  implicit val decoderTargetUpdateRequest: Decoder[TargetUpdateRequest] = deriveDecoder[TargetUpdateRequest].prepare {
    _.withFocus {
      _.mapObject { jsonObject =>
        val value = jsonObject("targetFormat")
        if (value.isEmpty || value.contains(Json.Null)) {
          ("targetFormat" -> TargetFormat.BINARY.asJson) +: jsonObject
        } else jsonObject
      }
    }
  }
  implicit val encoderTargetUpdateRequest: Encoder[TargetUpdateRequest] = deriveEncoder

  implicit val multiTargetUpdateCreatedEncoder: Encoder[MultiTargetUpdateRequest] = deriveEncoder
  implicit val multiTargetUpdateCreatedDecoder: Decoder[MultiTargetUpdateRequest] = deriveDecoder

  implicit val findAffectedRequestEncoder: Encoder[FindAffectedRequest] = deriveEncoder
  implicit val findAffectedRequestDecoder: Decoder[FindAffectedRequest] = deriveDecoder

  implicit val findImageCountEncoder: Encoder[FindImageCount] = deriveEncoder
  implicit val findImageCountDecoder: Decoder[FindImageCount] = deriveDecoder

  implicit val ecuInfoImageEncoder: Encoder[EcuInfoImage] = deriveEncoder
  implicit val ecuInfoImageDecoder: Decoder[EcuInfoImage] = deriveDecoder

  implicit val ecuInfoResponseEncoder: Encoder[EcuInfoResponse] = deriveEncoder
  implicit val ecuInfoResponseDecoder: Decoder[EcuInfoResponse] = deriveDecoder

  implicit val queueResponseEncoder: Encoder[QueueResponse] = deriveEncoder
  implicit val queueResponseDecoder: Decoder[QueueResponse] = deriveDecoder

  implicit val autoUpdateEncoder: Encoder[AutoUpdate] = deriveEncoder
  implicit val autoUpdateDecoder: Decoder[AutoUpdate] = deriveDecoder
}

object AkkaHttpUnmarshallingSupport {
  import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
  import akka.http.scaladsl.util.FastFuture

  implicit val ecuSerial: FromStringUnmarshaller[EcuSerial] =
    Unmarshaller{ec => x => FastFuture(x.refineTry[ValidEcuSerial])}
}
