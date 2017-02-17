package com.advancedtelematic.director.data

import com.advancedtelematic.libtuf.data.TufDataType.{KeyType, RoleType}
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientHashes => Hashes}
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.Json
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.data.{CirceEnum, SlickEnum}

object FileCacheRequestStatus extends CirceEnum with SlickEnum {
  type Status = Value

  val SUCCESS, ERROR, PENDING = Value
}

object DataType {
  import KeyType.KeyType
  import RoleType.RoleType

  final case class ValidEcuSerial()
  type EcuSerial = Refined[String, ValidEcuSerial]
  implicit val validEcuSerial: Validate.Plain[String, ValidEcuSerial] = ValidationUtils.validInBetween(min = 1, max = 64, ValidEcuSerial())

  final case class FileInfo(hashes: Hashes, length: Int)
  final case class Image(filepath: String, fileinfo: FileInfo)

  final case class Crypto(method: KeyType, publicKey: String) // String??

  final case class Ecu(ecuSerial: EcuSerial, device: Uuid, namespace: Namespace, primary: Boolean, crypto: Crypto)

  final case class CurrentImage (ecuSerial: EcuSerial, image: Image, attacksDetected: String)

  final case class EcuTarget(version: Int, ecuIdentifier: EcuSerial, image: Image)

  final case class DeviceTargets(device: Uuid, latestVersion: Int)

  final case class DeviceCurrentTarget(device: Uuid, targetVersion: Int)

  final case class FileCache(role: RoleType, version: Int, device: Uuid, file: Json)

  final case class FileCacheRequest(namespace: Namespace, version: Int, device: Uuid, status: FileCacheRequestStatus.Status)
}
