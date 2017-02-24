package com.advancedtelematic.director.data

import com.advancedtelematic.libats.codecs.{CirceEnum, SlickEnum}
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientHashes => Hashes, ClientKey}
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, RoleType}
import com.advancedtelematic.libtuf.data.UUIDKey.{UUIDKey, UUIDKeyObj}
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.Json
import java.util.UUID

object FileCacheRequestStatus extends CirceEnum with SlickEnum {
  type Status = Value

  val SUCCESS, ERROR, PENDING = Value
}

object DataType {
  import RoleType.RoleType

  final case class DeviceId(uuid: UUID) extends UUIDKey
  object DeviceId extends UUIDKeyObj[DeviceId]

  final case class Namespace(get: String) extends AnyVal

  final case class ValidEcuSerial()
  type EcuSerial = Refined[String, ValidEcuSerial]
  implicit val validEcuSerial: Validate.Plain[String, ValidEcuSerial] = ValidationUtils.validInBetween(min = 1, max = 64, ValidEcuSerial())

  final case class FileInfo(hashes: Hashes, length: Int)
  final case class Image(filepath: String, fileinfo: FileInfo)

  final case class Ecu(ecuSerial: EcuSerial, device: DeviceId, namespace: Namespace, primary: Boolean, clientKey: ClientKey)

  final case class CurrentImage (ecuSerial: EcuSerial, image: Image, attacksDetected: String)

  final case class EcuTarget(version: Int, ecuIdentifier: EcuSerial, image: Image)

  final case class DeviceTargets(device: DeviceId, latestVersion: Int)

  final case class DeviceCurrentTarget(device: DeviceId, targetVersion: Int)

  final case class FileCache(role: RoleType, version: Int, device: DeviceId, file: Json)

  final case class FileCacheRequest(namespace: Namespace, version: Int, device: DeviceId, status: FileCacheRequestStatus.Status)

  final case class RepoName(namespace: Namespace, repoId: RepoId)

  final case class RootFile(namespace: Namespace, rootFile: Json)
}
