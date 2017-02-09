package com.advancedtelematic.director.data

import eu.timepit.refined.api.{Refined, Validate}
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.data.{CirceEnum, SlickEnum}

object SignatureMethod extends CirceEnum with SlickEnum{
  type SignatureMethod = Value

  val RSASSA_PSS = Value("rsassa-pss")
}

object DataType {
  import SignatureMethod._

  final case class ValidEcuSerial()
  type EcuSerial = Refined[String, ValidEcuSerial]
  implicit val validEcuSerial: Validate.Plain[String, ValidEcuSerial] = ValidationUtils.validInBetween(min = 1, max = 64, ValidEcuSerial())

  final case class ValidKeyId()
  type KeyId = Refined[String, ValidKeyId]
  implicit val validKeyId: Validate.Plain[String, ValidKeyId] = ValidationUtils.validHexValidation(specificLength = Some(64), ValidKeyId())

  final case class ValidSha256()
  type Sha256 = Refined[String, ValidSha256]
  implicit val validSha256: Validate.Plain[String, ValidSha256] = ValidationUtils.validHash(64, "sha256", ValidSha256())

  final case class ValidSha512()
  type Sha512 = Refined[String, ValidSha512]
  implicit val validSha512: Validate.Plain[String, ValidSha512] = ValidationUtils.validHash(128, "sha512", ValidSha512())

  final case class ValidHexString()
  type HexString = Refined[String, ValidHexString]
  implicit val validHexString: Validate.Plain[String, ValidHexString] = ValidationUtils.validHexValidation(specificLength = None, ValidHexString())

  final case class Hashes(sha256: Sha256, sha512: Sha512)

  final case class FileInfo(hashes: Hashes, length: Int)
  final case class Image(filepath: String, fileinfo: FileInfo)

  final case class Crypto(method: SignatureMethod, publicKey: String) // String??

  final case class Ecu(ecuSerial: EcuSerial, device: Uuid, namespace: Namespace, primary: Boolean, crypto: Crypto)

  final case class Signature(sig: HexString, method: SignatureMethod = SignatureMethod.RSASSA_PSS)
}
