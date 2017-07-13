package com.advancedtelematic.director.manifest

import cats.syntax.either._
import com.advancedtelematic.director.data.DataType.Ecu
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest, LegacyDeviceManifest}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.libats.messaging_datatype.DataType.EcuSerial
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.crypt.Sha256Digest
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{ClientSignature, Signature, SignedPayload}
import io.circe.{Encoder, Json}
import io.circe.syntax._
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object Verifier {
  type Verifier = (Signature, Array[Byte]) => Try[Boolean]
  val alwaysAccept: Verifier = (_,_) => Success(true)
  val alwaysReject: Verifier = (_,_) => Success(false)

}

object Verify {
  import Verifier.Verifier

  val log = LoggerFactory.getLogger(this.getClass)

  def checkSigned[T](what: SignedPayload[T], checkSignature: Verifier) (implicit encoder: Encoder[T]): Try[T] = {
    val data = what.signed.asJson.canonical.getBytes

    if( log.isDebugEnabled ) {
      log.debug(s"Verifying signature of ${what.signed.asJson.canonical}, data digest: ${Sha256Digest.digest(data)}")
    }

    @tailrec
    def go(sigs: Seq[ClientSignature]): Try[T] = sigs match {
      case Seq() => Success(what.signed)
      case sig +: xs => checkSignature(sig.toSignature, data) match {
        case Success(b) if b => go(xs)
        case Success(b) => Failure(Errors.SignatureNotValid)
        case Failure(err) => Failure(err)
      }
    }

    what.signatures match {
      case Seq() => Failure(Errors.EmptySignatureList)
      case xs => go(xs)
    }
  }

  private def checkEcuManifest(verifier: ClientKey => Verifier, ecu: Ecu, ecuSerial: EcuSerial, jsonBlob: Json): Try[EcuManifest] = for {
    sEcumanifest <- jsonBlob.as[SignedPayload[EcuManifest]].toTry
    () <- Either.cond(sEcumanifest.signed.ecu_serial == ecuSerial, (), Errors.WrongEcuSerialInEcuManifest).toTry
    ecuManifest <- checkSigned(sEcumanifest, verifier(ecu.clientKey))
  } yield ecuManifest

  private def checkDeviceManifest[T : Encoder](getPrimary: T => EcuSerial, ecuVersionManifests : T => Map[EcuSerial, Json],
                                               ecusForDevice: Seq[Ecu], verifier: ClientKey => Verifier, signedDevMan: SignedPayload[T]): Try[Seq[EcuManifest]] = {
    val ecuMap = ecusForDevice.map(x => x.ecuSerial -> x).toMap

    def findEcu(ecuSerial: EcuSerial)(handler: PartialFunction[Ecu, Throwable] = PartialFunction.empty): Try[Ecu] =
      ecuMap.get(ecuSerial) match {
        case None => Failure(Errors.EcuNotFound)
        case Some(ecu) => if (handler.isDefinedAt(ecu)) Failure(handler(ecu)) else Success(ecu)
      }

    for {
      primaryEcu <- findEcu(getPrimary(signedDevMan.signed)){
        case ecu if !ecu.primary => Errors.EcuNotPrimary
      }
      devMan <- checkSigned(signedDevMan, verifier(primaryEcu.clientKey))
      verifiedEcus = ecuVersionManifests(devMan).map { case (ecuSerial, jsonBlob) =>
        findEcu(ecuSerial)().flatMap(checkEcuManifest(verifier, _, ecuSerial, jsonBlob))
      }.toSeq
    } yield verifiedEcus.collect { case Success(x) => x }
  }

  def legacyDeviceManifest(ecusForDevice: Seq[Ecu], verifier: ClientKey => Verifier, signedDevMan: SignedPayload[LegacyDeviceManifest]): Try[Seq[EcuManifest]] =
    checkDeviceManifest[LegacyDeviceManifest](_.primary_ecu_serial, _.ecu_version_manifest.map(x => x.signed.ecu_serial -> x.asJson).toMap,
                                              ecusForDevice, verifier, signedDevMan)

  def deviceManifest(ecusForDevice: Seq[Ecu], verifier: ClientKey => Verifier, signedDevMan: SignedPayload[DeviceManifest]): Try[Seq[EcuManifest]] =
    checkDeviceManifest[DeviceManifest](_.primary_ecu_serial, _.ecu_version_manifests, ecusForDevice, verifier, signedDevMan)
}
