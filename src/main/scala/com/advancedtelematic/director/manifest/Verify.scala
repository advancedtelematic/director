package com.advancedtelematic.director.manifest

import cats.syntax.either._
import com.advancedtelematic.director.data.DataType.Ecu
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.libats.messaging_datatype.DataType.EcuSerial
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.crypt.Sha256Digest
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{ClientSignature, Signature, SignedPayload}
import io.circe.Encoder
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

  def tryCondition(cond: Boolean, err: => Throwable): Try[Unit] =
    if (cond) {
      Success(())
    } else {
      Failure(err)
    }

  def deviceManifest(ecusForDevice: Seq[Ecu],
                        verifier: ClientKey => Verifier,
                        signedDevMan: SignedPayload[DeviceManifest]): Try[Seq[EcuManifest]] = {
    val ecuMap = ecusForDevice.map(x => x.ecuSerial -> x).toMap

    def findEcu(ecuSerial: EcuSerial)(handler: PartialFunction[Ecu, Throwable] = PartialFunction.empty): Try[Ecu] =
      ecuMap.get(ecuSerial) match {
        case None => Failure(Errors.EcuNotFound)
        case Some(ecu) => if (handler.isDefinedAt(ecu)) Failure(handler(ecu)) else Success(ecu)
      }

    for {
      primaryEcu <- findEcu(signedDevMan.signed.primary_ecu_serial){
        case ecu if !ecu.primary => Errors.EcuNotPrimary
      }
      devMan <- checkSigned(signedDevMan, verifier(primaryEcu.clientKey))
      verifiedEcus = devMan.ecu_version_manifests.map { case (ecuSerial, jsonBlob) =>
        for {
          ecu <- findEcu(ecuSerial)()
          sEcumanifest <- jsonBlob.as[SignedPayload[EcuManifest]].toTry
          () <- Either.cond(sEcumanifest.signed.ecu_serial == ecuSerial, (), Errors.WrongEcuSerialInEcuManifest).toTry
          ecuManifest <- checkSigned(sEcumanifest, verifier(ecu.clientKey))
        } yield ecuManifest
      }.toSeq
    } yield verifiedEcus.collect { case Success(x) => x }
  }
}
