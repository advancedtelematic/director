package com.advancedtelematic.director.data

import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.HttpCodecs._
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor, Json, JsonObject}

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

  implicit val decoderTargetCustomUri: Decoder[TargetCustomUri] = deriveDecoder
  implicit val encoderTargetCustomUri: Encoder[TargetCustomUri] = deriveEncoder

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


  implicit val decoderDeviceManifestEcuSigned: Decoder[DeviceManifestEcuSigned] = Decoder.instance { cursor =>
    for {
      primaryEcu <- cursor.downField("primary_ecu_serial").as[EcuIdentifier]
      installationReport <- cursor.downField("installation_report").as[Option[InstallationReportEntity]]
      ecuManifests <- decodeEcuManifests(cursor)
    } yield DeviceManifestEcuSigned(primaryEcu, ecuManifests, installationReport)
  }

  private def decodeEcuManifests(cursor: HCursor): Decoder.Result[Map[EcuIdentifier, Json]] =
      cursor.downField("ecu_version_manifests").as[Option[Map[EcuIdentifier, Json]]].flatMap {
        case Some(map) => Right(map)
        // the legacy format
        case None => cursor.downField("ecu_version_manifest").as[Seq[Json]].flatMap { signedEcus =>
          signedEcus.toList.traverse(sEcu => sEcu.hcursor.downField("signed").downField("ecu_serial").as[EcuIdentifier].map(_ -> sEcu))
            .map(ecus => ecus.toMap)
        }
      }

  implicit val decoderInstallationItem: Decoder[InstallationItem] = deriveDecoder
  implicit val encoderInstallationItem: Encoder[InstallationItem] = deriveEncoder

  implicit val decoderInstallationReport: Decoder[InstallationReport] = deriveDecoder
  implicit val encoderInstallationReport: Encoder[InstallationReport] = deriveEncoder

  implicit val decoderInstallationReportEntity: Decoder[InstallationReportEntity] = deriveDecoder
  implicit val encoderInstallationReportEntity: Encoder[InstallationReportEntity] = deriveEncoder

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

  implicit val assignUpdateRequestEncoder: Encoder[AssignUpdateRequest] = deriveEncoder
  implicit val assignUpdateRequestDecoder: Decoder[AssignUpdateRequest] = deriveDecoder

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

  implicit val targetsCustomEncoder: Encoder[TargetsCustom] = deriveEncoder
  implicit val targetsCustomDecoder: Decoder[TargetsCustom] = deriveDecoder
}

object AkkaHttpUnmarshallingSupport {
  import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
  import akka.http.scaladsl.util.FastFuture

  implicit val ecuIdUnmarshaller: FromStringUnmarshaller[EcuIdentifier] =
    Unmarshaller { _ => x => FastFuture(EcuIdentifier(x).toTry) }
}
