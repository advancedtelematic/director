package com.advancedtelematic.director.manifest

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.DeviceManifest
import com.advancedtelematic.director.db.EcuRepositorySupport
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.{SignedPayload, TufKey}
import io.circe.Json
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

class DeviceManifestProcess()(implicit val db: Database, val ec: ExecutionContext) extends EcuRepositorySupport {
  import cats.syntax.either._

  private val _log = LoggerFactory.getLogger(this.getClass)

  import com.advancedtelematic.libtuf.crypt.SignedPayloadSignatureOps._

  def validateManifestSignatures(ns: Namespace, deviceId: DeviceId, json: SignedPayload[Json]): Future[ValidatedNel[String, DeviceManifest]] = async {
    val primaryValidation = await(validateManifestPrimarySignatures(ns, deviceId, json))
    val deviceEcus = await(ecuRepository.findFor(deviceId)).mapValues(_.publicKey)

    primaryValidation.andThen { manifest =>
      validateManifestSecondarySignatures(deviceEcus, manifest)
    }
  }

  private def validateManifestSecondarySignatures(deviceEcuKeys: Map[EcuIdentifier, TufKey], deviceManifest: DeviceManifest): ValidatedNel[String, DeviceManifest] = {
    val verify = deviceManifest.ecu_version_manifests.map { case (ecuSerial, ecuManifest) =>
      deviceEcuKeys.get(ecuSerial) match {
        case None => Invalid(NonEmptyList.of(s"Device has no ECU with $ecuSerial"))
        case Some(key) =>
          if (ecuManifest.isValidFor(key))
            Valid(ecuManifest)
          else
            Invalid(NonEmptyList.of(s"ecu manifest for $ecuSerial not signed with key ${key.id}"))
      }
    }.toList.sequence

    verify.map(_ => deviceManifest)
  }

  private def validateManifestPrimarySignatures(ns: Namespace, deviceId: DeviceId, manifestJson: SignedPayload[Json]): Future[ValidatedNel[String, DeviceManifest]] = {
    ecuRepository.findDevicePrimary(ns, deviceId).map { primary =>
      if (manifestJson.isValidFor(primary.publicKey)) {
        val manifest = manifestJson.json.as[DeviceManifest]
        manifest.leftMap(_.message).toValidatedNel
      } else {
        Invalid(NonEmptyList.of(s"Invalid primary ecu (${primary.ecuSerial}) signature for key ${primary.publicKey.id}"))
      }
    }
  }
}



