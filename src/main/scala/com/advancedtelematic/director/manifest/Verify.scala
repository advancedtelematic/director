package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.data.DataType.{Crypto, Ecu}
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.Utility._
import com.advancedtelematic.libtuf.data.TufDataType.{ClientSignature, Signature, SignedPayload}
import io.circe.Encoder
import io.circe.syntax._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object Verifier {
  type Verifier = (Signature, Array[Byte]) => Try[Boolean]
  val alwaysAccept: Verifier = (_,_) => Success(true)
  val alwaysReject: Verifier = (_,_) => Success(false)

}

object Verify {
  import Verifier.Verifier

  def checkSigned[T](what: SignedPayload[T], checkSignature: Verifier) (implicit encoder: Encoder[T]): Try[T] = {
    val data = what.signed.asJson.getCanonicalBytes

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
                     verifier: Crypto => Verifier,
                     signedDevMan: SignedPayload[DeviceManifest]): Try[Seq[EcuManifest]] = {
    val ecuMap = ecusForDevice.groupBy(_.ecuSerial).mapValues(_.head)
    for {
      primaryEcu <- ecuMap.get(signedDevMan.signed.primary_ecu_serial)
                          .fold[Try[Ecu]](Failure(Errors.EcuNotFound))(Success(_))
      _ <- tryCondition(primaryEcu.primary, Errors.EcuNotPrimary)
      devMan <- checkSigned(signedDevMan, verifier(primaryEcu.crypto))
      verifiedEcu = devMan.ecu_version_manifest.map{ case sEcu =>
        ecuMap.get(sEcu.signed.ecu_serial) match {
          case None => Failure(Errors.EcuNotFound)
          case Some(ecu) => checkSigned(sEcu, verifier(ecu.crypto))
        }
      }
    } yield verifiedEcu.collect { case Success(x) => x}
  }
}
