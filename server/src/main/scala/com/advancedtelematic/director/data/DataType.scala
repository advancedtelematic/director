package com.advancedtelematic.director.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.FileCacheRequestStatus.FileCacheRequestStatus
import com.advancedtelematic.libats.codecs.CirceEnum
import com.advancedtelematic.libats.data.DataType.{Checksum, HashMethod, Namespace, ValidChecksum}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libtuf.data.ClientDataType.ClientHashes
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, RoleType, TargetFilename, TargetName, TufKey}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import eu.timepit.refined.api.Refined
import io.circe.Json
import java.time.Instant

import com.advancedtelematic.libats.slick.codecs.SlickEnum

object FileCacheRequestStatus extends CirceEnum with SlickEnum {
  type FileCacheRequestStatus = Value

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

  // we need to figure out a better way to generalise this, but
  // Map[HashMethod, Refined[String, ValidChecksum]] dosen't work
  // for two reasons:
  //   1. The standard decoder don't ignore unknown HashMethods
  //   2. The ValidChecksum contains a length
  final case class Hashes(sha256: Refined[String, ValidChecksum]) {
    def toClientHashes: ClientHashes = Map(HashMethod.SHA256 -> sha256)
  }

  final case class FileInfo(hashes: Hashes, length: Long)
  final case class Image(filepath: TargetFilename, fileinfo: FileInfo) {
    def toTargetUpdate: TargetUpdate = {
      val checksum = Checksum(HashMethod.SHA256, fileinfo.hashes.sha256)
      TargetUpdate(filepath, checksum, fileinfo.length)
    }
  }

  // TODO: CustomImage should not even exist, why not just use TargetCustomImage ?
  final case class CustomImage(image: Image, uri: Uri, diffFormat: Option[TargetFormat], updateId: Option[UpdateId] = None)
  final case class TargetCustomImage(image: Image, hardwareId: HardwareIdentifier, uri: Uri, diff: Option[DiffInfo], updateId: Option[UpdateId] = None)

  final case class DiffInfo(checksum: Checksum, size: Long, url: Uri)

  final case class TargetCustomUri(hardwareId: HardwareIdentifier, uri: Uri, diff: Option[DiffInfo])

  // TODO: Why, why do we have 4 custom image case classes?
  final case class TargetCustom(@deprecated("use ecuIdentifiers", "") ecuIdentifier: EcuSerial,
                                hardwareId: HardwareIdentifier,
                                uri: Uri, diff: Option[DiffInfo],
                                ecuIdentifiers: Map[EcuSerial, TargetCustomUri], updateId: Option[UpdateId] = None)

  final case class Ecu(ecuSerial: EcuSerial, device: DeviceId, namespace: Namespace, primary: Boolean,
                       hardwareId: HardwareIdentifier, tufKey: TufKey) {
    def keyType = tufKey.keytype
    def publicKey = tufKey.keyval
  }

  final case class CurrentImage (namespace: Namespace, ecuSerial: EcuSerial, image: Image, attacksDetected: String)

  final case class EcuTarget(namespace: Namespace, version: Int, ecuIdentifier: EcuSerial, customImage: CustomImage)

  final case class DeviceUpdateTarget(device: DeviceId, updateId: Option[UpdateId], targetVersion: Int, inFlight: Boolean)

  final case class DeviceCurrentTarget(device: DeviceId, targetVersion: Int)

  final case class FileCache(role: RoleType, version: Int, device: DeviceId, expires: Instant, file: Json)

  final case class FileCacheRequest(namespace: Namespace, targetVersion: Int, device: DeviceId, updateId: Option[UpdateId],
                                    status: FileCacheRequestStatus, timestampVersion: Int)

  final case class RepoName(namespace: Namespace, repoId: RepoId)

  final case class MultiTargetUpdateRow(id: UpdateId, hardwareId: HardwareIdentifier, fromTarget: Option[TargetUpdate],
                                        toTarget: TargetUpdate, targetFormat: TargetFormat, generateDiff: Boolean,
                                        namespace: Namespace) {
    lazy val targetUpdateRequest: TargetUpdateRequest = TargetUpdateRequest(fromTarget, toTarget, targetFormat, generateDiff)
  }

  final case class TargetUpdate(target: TargetFilename, checksum: Checksum, targetLength: Long) {
    lazy val image: Image = {
      val hashes = Hashes(checksum.hash)
      Image(target, FileInfo(hashes, targetLength))
    }
  }

  // this type have a custom encoder
  final case class TargetUpdateRequest(from: Option[TargetUpdate], to: TargetUpdate, targetFormat: TargetFormat,
                                       generateDiff: Boolean)

  final case class MultiTargetUpdateRequest(targets: Map[HardwareIdentifier, TargetUpdateRequest]) {
    def multiTargetUpdateRows(id: UpdateId, namespace: Namespace): Seq[MultiTargetUpdateRow] =
      targets.toSeq.map { case (hardwareId, TargetUpdateRequest(from, target, format, diff)) =>
        MultiTargetUpdateRow(id = id, hardwareId = hardwareId, fromTarget = from,
                             toTarget = target, targetFormat = format, generateDiff = diff,
                             namespace = namespace)
      }
  }

  final case class LaunchedMultiTargetUpdate(device: DeviceId, update: UpdateId, timestampVersion: Int,
                                             status: LaunchedMultiTargetUpdateStatus.Status)

  final case class AutoUpdate(namespace: Namespace, device: DeviceId, ecuSerial: EcuSerial, targetName: TargetName)
}
