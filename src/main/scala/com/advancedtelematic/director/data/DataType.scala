package com.advancedtelematic.director.data

import eu.timepit.refined.api.{Refined, Validate}
import io.circe._
import java.time.Instant
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.data.{CirceEnum, SlickEnum}

object SignatureMethod extends CirceEnum with SlickEnum{
  type SignatureMethod = Value

  val RSASSA_PSS = Value("rsassa-pss")
}


object DataType {
  import ValidationUtils._
  import SignatureMethod._

  case class ValidEcuSerial()
  type EcuSerial = Refined[String, ValidEcuSerial]
  implicit val validEcuSerial: Validate.Plain[String, ValidEcuSerial] = validInBetween(min = 1, max = 64, ValidEcuSerial())

  case class ValidKeyId()
  type KeyId = Refined[String, ValidKeyId]
  implicit val validKeyId: Validate.Plain[String, ValidKeyId] = validHexValidation(specificLength = Some(64), ValidKeyId())

  case class ValidSha256()
  type Sha256 = Refined[String, ValidSha256]
  implicit val validSha256: Validate.Plain[String, ValidSha256] = validHash(64, "sha256", ValidSha256())

  case class ValidSha512()
  type Sha512 = Refined[String, ValidSha512]
  implicit val validSha512: Validate.Plain[String, ValidSha512] = validHash(128, "sha512", ValidSha512())

  case class ValidHexString()
  type HexString = Refined[String, ValidHexString]
  implicit val validHexString: Validate.Plain[String, ValidHexString] = validHexValidation(specificLength = None, ValidHexString())

  final case class Hashes(sha256: Sha256, sha512: Sha512)

  final case class FileInfo(hashes: Hashes, length: Int)
  final case class InstalledImage(filepath: String, fileinfo: FileInfo)

  final case class Crypto(method: SignatureMethod, publicKey: String) // String??

  final case class Ecu(ecuSerial: EcuSerial, device: Uuid, namespace: Namespace, primary: Boolean, crypto: Crypto)

  final case class Signature(sig: HexString, method: SignatureMethod = SignatureMethod.RSASSA_PSS)

  /*** Device Request ***/

  final case class ClientSignature(method: SignatureMethod, sig: HexString, keyid: KeyId) {
    def toSignature: Signature = Signature(method = method, sig = sig)
  }
  final case class SignedPayload[T](signatures: Seq[ClientSignature], signed: T)

  final case class EcuManifest(timeserver_time: Instant,
                               installed_image: InstalledImage,
                               previous_timeserver_time: Instant,
                               ecu_serial: EcuSerial,
                               attacks_detected: String)

  final case class DeviceManifest(vin: Uuid,
                                  primary_ecu_serial: EcuSerial,
                                  ecu_version_manifest: Seq[SignedPayload[EcuManifest]])


  /*** Admin Request ***/

  final case class RegisterEcu(ecu_serial: EcuSerial, crypto: Crypto)

  final case class RegisterDevice(vin: Uuid, primary_ecu_serial: EcuSerial, ecus: Seq[RegisterEcu])

  /*** Utility ***/

  implicit class ToCanonicalJsonOps(value: Json) {
    def getCanonicalBytes: Array[Byte] = canonical.getBytes
    def canonical: String = generate(value).noSpaces

    private def generate(value: Json): Json = value.arrayOrObject[Json](
      value,
      array => Json.fromValues(array.map(generate)),
      obj => Json.fromJsonObject(
        JsonObject.fromIterable {
          obj.toList
            .map { case (k, v) => k -> generate(v)
          }.sortBy(_._1)
        }
      )
    )
  }
}

protected[data] object ValidationUtils {
  def validHex(length: Option[Long], str: String): Boolean = {
    length.map(str.length == _).getOrElse(true) && str.forall(h => ('0' to '9').contains(h) || ('a' to 'f').contains(h))
  }

  def validHexValidation[T](specificLength: Option[Long], proof: T): Validate.Plain[String, T] =
    Validate.fromPredicate(
      str => validHex(specificLength, str),
      str => s"$str is not a ${specificLength.getOrElse("")} hex string",
      proof
    )

  def validInBetween[T](min: Long, max: Long, proof: T): Validate.Plain[String, T] =
    Validate.fromPredicate(
      str => str.length >= min && str.length <= max,
      str => s"$str is not between ${min} and $max chars long",
      proof
    )

  def validHash[T](length: Long, name: String, proof: T): Validate.Plain[String, T] =
    Validate.fromPredicate(
      hash => validHex(Some(length),hash),
      hash => s"$hash must be a $name",
      proof
    )
}
