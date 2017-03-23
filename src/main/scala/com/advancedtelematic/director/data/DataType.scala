package com.advancedtelematic.director.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.libats.codecs.{CirceEnum, SlickEnum}
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientKey, ClientHashes => Hashes}
import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, RepoId, RoleType}
import com.advancedtelematic.libtuf.data.UUIDKey.{UUIDKey, UUIDKeyObj}
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.Json
import java.util.UUID

import com.advancedtelematic.libats.data.Namespace

object FileCacheRequestStatus extends CirceEnum with SlickEnum {
  type Status = Value

  val SUCCESS, ERROR, PENDING = Value
}

object DataType {
  import RoleType.RoleType

  final case class DeviceId(uuid: UUID) extends UUIDKey
  object DeviceId extends UUIDKeyObj[DeviceId]

  final case class UpdateId(uuid: UUID) extends UUIDKey
  object UpdateId extends UUIDKeyObj[UpdateId]

  final case class ValidEcuSerial()
  type EcuSerial = Refined[String, ValidEcuSerial]
  implicit val validEcuSerial: Validate.Plain[String, ValidEcuSerial] = ValidationUtils.validInBetween(min = 1, max = 64, ValidEcuSerial())

  final case class FileInfo(hashes: Hashes, length: Long)
  final case class Image(filepath: String, fileinfo: FileInfo)

  final case class CustomImage(filepath: String, fileinfo: FileInfo, uri: Uri) {
    def image: Image = Image(filepath, fileinfo)
  }


  final case class Ecu(ecuSerial: EcuSerial, device: DeviceId, namespace: Namespace, primary: Boolean, clientKey: ClientKey)

  final case class CurrentImage (ecuSerial: EcuSerial, image: Image, attacksDetected: String)

  final case class EcuTarget(version: Int, ecuIdentifier: EcuSerial, image: CustomImage)

  final case class DeviceUpdateTarget(device: DeviceId, updateId: Option[UpdateId], targetVersion: Int)

  final case class DeviceCurrentTarget(device: DeviceId, targetVersion: Int)

  final case class FileCache(role: RoleType, version: Int, device: DeviceId, file: Json)

  final case class FileCacheRequest(namespace: Namespace, targetVersion: Int, device: DeviceId,
                                    status: FileCacheRequestStatus.Status, timestampVersion: Int)

  final case class RepoName(namespace: Namespace, repoId: RepoId)

  final case class RootFile(namespace: Namespace, rootFile: Json)

  final case class MultiTargetUpdate(id: UpdateId, hardwareId: String, target: String, checksum: Checksum,
                                     targetLength: Long, namespace: Namespace) {
    lazy val image: Image = {
      val clientHash = Map(checksum.method -> checksum.hash)
      Image(target, FileInfo(clientHash, targetLength))
    }
  }

  final case class MultiTargetUpdateRequest(id: UpdateId, hardwareId: String, target: String, checksum: Checksum,
                                            targetLength: Long)

}
