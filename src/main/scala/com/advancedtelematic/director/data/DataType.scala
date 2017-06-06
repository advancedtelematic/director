package com.advancedtelematic.director.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.libats.codecs.CirceEnum
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuSerial, DeviceId, TargetFilename, UpdateId}
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientKey, ClientHashes => Hashes}
import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, RepoId, RoleType}
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.Json
import java.time.Instant

import com.advancedtelematic.libats.slick.codecs.SlickEnum

object FileCacheRequestStatus extends CirceEnum with SlickEnum {
  type Status = Value

  val SUCCESS, ERROR, PENDING = Value
}

object LaunchedMultiTargetUpdateStatus extends CirceEnum with SlickEnum {
  type Status = Value

  val Pending, InFlight, Canceled, Failed, Finished = Value
}

object UpdateType extends CirceEnum with SlickEnum {
  type UpdateType = Value

  val OLD_STYLE_CAMPAIGN, MULTI_TARGET_UPDATE = Value
}

object DataType {
  import RoleType.RoleType

  final case class ValidHardwareIdentifier()
  type HardwareIdentifier = Refined[String, ValidHardwareIdentifier]
  implicit val validHardwareIdentifier: Validate.Plain[String, ValidHardwareIdentifier] = ValidationUtils.validInBetween(min = 0, max = 200, ValidHardwareIdentifier())

  final case class FileInfo(hashes: Hashes, length: Long)
  final case class Image(filepath: TargetFilename, fileinfo: FileInfo)

  final case class StaticDelta(checksum: Checksum, length: Long)
  final case class TargetCustom(ecu_serial: EcuSerial, hardwareId: HardwareIdentifier, uri: Uri, delta: Option[StaticDelta])
  final case class CustomImage(image: Image, hardwareId: HardwareIdentifier, uri: Uri, delta: Option[StaticDelta])

  final case class Ecu(ecuSerial: EcuSerial, device: DeviceId, namespace: Namespace, primary: Boolean,
                       hardwareId: HardwareIdentifier, clientKey: ClientKey)

  final case class CurrentImage (namespace: Namespace, ecuSerial: EcuSerial, image: Image, attacksDetected: String)

  final case class EcuTarget(namespace: Namespace, version: Int, ecuIdentifier: EcuSerial, image: Image, uri: Uri, delta: Option[StaticDelta])

  final case class DeviceUpdateTarget(device: DeviceId, updateId: Option[UpdateId], targetVersion: Int)

  final case class DeviceCurrentTarget(device: DeviceId, targetVersion: Int)

  final case class FileCache(role: RoleType, version: Int, device: DeviceId, expires: Instant, file: Json)

  final case class FileCacheRequest(namespace: Namespace, targetVersion: Int, device: DeviceId, updateId: Option[UpdateId],
                                    status: FileCacheRequestStatus.Status, timestampVersion: Int)

  final case class RepoName(namespace: Namespace, repoId: RepoId)

  final case class RootFile(namespace: Namespace, rootFile: Json)

  final case class MultiTargetUpdate(id: UpdateId, hardwareId: HardwareIdentifier, fromTarget: Option[TargetUpdate],
                                     target: TargetFilename, checksum: Checksum,
                                     targetLength: Long, namespace: Namespace) {
    lazy val image: Image = {
      val clientHash = Map(checksum.method -> checksum.hash)
      Image(target, FileInfo(clientHash, targetLength))
    }
  }

  final case class MultiTargetUpdateDelta(id: UpdateId, hardwareId: HardwareIdentifier, checksum: Checksum, size: Long)

  object MultiTargetUpdate {
    def apply(mtu: MultiTargetUpdateRequest, id: UpdateId, namespace: Namespace): Seq[MultiTargetUpdate] =
      mtu.targets.toSeq.map {case (hardwareId, TargetUpdateRequest(from, target)) =>
        MultiTargetUpdate(id = id, hardwareId = hardwareId, fromTarget = from,
                          target = target.target, checksum = target.checksum,
                          targetLength = target.targetLength, namespace = namespace)
      }
  }

  final case class TargetUpdate(target: TargetFilename, checksum: Checksum, targetLength: Long) {
    lazy val image: Image = {
      val clientHash = Map(checksum.method -> checksum.hash)
      Image(target, FileInfo(clientHash, targetLength))
    }
  }

  final case class TargetUpdateRequest(from: Option[TargetUpdate], to: TargetUpdate)

  final case class MultiTargetUpdateRequest(targets: Map[HardwareIdentifier, TargetUpdateRequest])

  final case class MultiTargetUpdateDeltaRegistration(deltas: Map[HardwareIdentifier, StaticDelta])

  final case class LaunchedMultiTargetUpdate(device: DeviceId, update: UpdateId, timestampVersion: Int,
                                             status: LaunchedMultiTargetUpdateStatus.Status)

}
