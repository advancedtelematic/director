package com.advancedtelematic.director.data

import akka.http.scaladsl.server.PathMatchers
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.Json
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.data.{CirceEnum, SlickEnum}
import scala.util.Try

object SignatureMethod extends CirceEnum with SlickEnum {
  type SignatureMethod = Value

  val RSASSA_PSS = Value("rsassa-pss")
}

object HashMethod extends CirceEnum {
  type HashMethod = Value

  val SHA256 = Value("sha256")
}

object Role extends CirceEnum with SlickEnum {
  type Role = Value

  val ROOT, SNAPSHOT, TARGETS, TIMESTAMP = Value

  val PathMatcher = PathMatchers.Segment.flatMap { str =>
    val role = str.stripSuffix(".json")
    Try{Role.withName(role.toUpperCase)}.toOption
  }
}

object FileCacheRequestStatus extends CirceEnum with SlickEnum {
  type Status = Value

  val SUCCESS, ERROR, PENDING = Value
}

object DataType {
  import SignatureMethod._
  import HashMethod.HashMethod

  final case class ValidEcuSerial()
  type EcuSerial = Refined[String, ValidEcuSerial]
  implicit val validEcuSerial: Validate.Plain[String, ValidEcuSerial] = ValidationUtils.validInBetween(min = 1, max = 64, ValidEcuSerial())

  final case class ValidKeyId()
  type KeyId = Refined[String, ValidKeyId]
  implicit val validKeyId: Validate.Plain[String, ValidKeyId] = ValidationUtils.validHexValidation(specificLength = Some(64), ValidKeyId())

  /*
  final case class ValidSha256()
  type Sha256 = Refined[String, ValidSha256]
  implicit val validSha256: Validate.Plain[String, ValidSha256] = ValidationUtils.validHash(64, "sha256", ValidSha256())

  final case class ValidSha512()
  type Sha512 = Refined[String, ValidSha512]
  implicit val validSha512: Validate.Plain[String, ValidSha512] = ValidationUtils.validHash(128, "sha512", ValidSha512())
   */

  final case class ValidHexString()
  type HexString = Refined[String, ValidHexString]
  implicit val validHexString: Validate.Plain[String, ValidHexString] = ValidationUtils.validHexValidation(specificLength = None, ValidHexString())

  // final case class Hashes(sha256: Sha256, sha512: Sha512)
  type Hashes = Map[HashMethod, HexString]

  final case class FileInfo(hashes: Hashes, length: Int)
  final case class Image(filepath: String, fileinfo: FileInfo)

  final case class Crypto(method: SignatureMethod, publicKey: String) // String??

  final case class Ecu(ecuSerial: EcuSerial, device: Uuid, namespace: Namespace, primary: Boolean, crypto: Crypto)

  final case class Signature(sig: HexString, method: SignatureMethod = SignatureMethod.RSASSA_PSS)

  final case class CurrentImage (ecuSerial: EcuSerial, image: Image, attacksDetected: String)

  final case class EcuTarget(version: Int, ecuIdentifier: EcuSerial, image: Image)

  final case class Snapshot(device: Uuid, device_version: Int, target_version: Int)

  final case class FileCache(role: Role.Role, version: Int, device: Uuid, file: Json)

  final case class FileCacheRequest(namespace: Namespace, version: Int, device: Uuid, status: FileCacheRequestStatus.Status)
}
