package com.advancedtelematic.director.manifest

import cats.Traverse
import cats.instances.try_._
import cats.instances.list._
import com.advancedtelematic.director.data.DataType.Ecu
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.libats.messaging_datatype.DataType.EcuSerial
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{ClientSignature, Signature, SignedPayload, TufKey}
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import io.circe.Json
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

  def checkSigned(what: SignedPayload[Json], checkSignature: Verifier): Try[Json] = {
    val data = what.signed.canonical.getBytes

    if( log.isDebugEnabled ) {
      log.debug(s"Verifying signature of ${what.signed.asJson.canonical}, data digest: ${Sha256Digest.digest(data)}")
    }

    @tailrec
    def go(sigs: Seq[ClientSignature]): Try[Json] = sigs match {
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

  private def checkEcuManifest(verifier: TufKey => Verifier, ecu: Ecu, ecuSerial: EcuSerial, jsonBlob: Json): Try[EcuManifest] = for {
    signedBlob <- jsonBlob.as[SignedPayload[Json]].toTry
    manifestBlob <- checkSigned(signedBlob, verifier(ecu.tufKey))
    ecuManifest <- manifestBlob.as[EcuManifest].toTry
    () <- Either.cond(ecuManifest.ecu_serial == ecuSerial, (), Errors.WrongEcuSerialInEcuManifest).toTry
  } yield ecuManifest

  def deviceManifest(ecusForDevice: Seq[Ecu], verifier: TufKey => Verifier, signedDevMan: SignedPayload[Json]): Try[Seq[EcuManifest]] = {
    val ecuMap = ecusForDevice.map(x => x.ecuSerial -> x).toMap

    def findEcu(ecuSerial: EcuSerial)(handler: PartialFunction[Ecu, Throwable] = PartialFunction.empty): Try[Ecu] =
      ecuMap.get(ecuSerial) match {
        case None => Failure(Errors.EcuNotFound)
        case Some(ecu) => if (handler.isDefinedAt(ecu)) Failure(handler(ecu)) else Success(ecu)
      }

    for {
      devMan <- signedDevMan.signed.as[DeviceManifest].toTry
      primaryEcu <- findEcu(devMan.primary_ecu_serial) {
        case ecu if !ecu.primary => Errors.EcuNotPrimary
      }
      _ <- checkSigned(signedDevMan, verifier(primaryEcu.tufKey))
      verifiedManifests = devMan.ecu_version_manifests.map { case (ecuSerial, jsonBlob) =>
        findEcu(ecuSerial)().flatMap(checkEcuManifest(verifier, _, ecuSerial, jsonBlob))
      }.toSeq.toList
      tryOfManifests <- Traverse[List].sequence(verifiedManifests)
    } yield tryOfManifests
  }
}
