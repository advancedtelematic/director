package com.advancedtelematic.director.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.FileCacheRequestStatus.FileCacheRequestStatus
import com.advancedtelematic.libats.data.DataType.{Checksum, CorrelationId, HashMethod, Namespace, ValidChecksum}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.ClientDataType.ClientHashes
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, RoleType, TargetFilename, TargetName, TufKey}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import eu.timepit.refined.api.Refined
import io.circe.Json
import java.time.Instant

import com.advancedtelematic.libats.data.EcuIdentifier


object FileCacheRequestStatus extends Enumeration {
  type FileCacheRequestStatus = Value

  val SUCCESS, ERROR, PENDING = Value
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

  final case class CustomImage(image: Image, uri: Uri, diffFormat: Option[TargetFormat])
  final case class TargetCustomImage(image: Image, hardwareId: HardwareIdentifier, uri: Uri, diff: Option[DiffInfo])

  final case class DiffInfo(checksum: Checksum, size: Long, url: Uri)

  final case class TargetCustomUri(hardwareId: HardwareIdentifier, uri: Uri, diff: Option[DiffInfo])
  final case class TargetCustom(@deprecated("use ecuIdentifiers", "") ecuIdentifier: EcuIdentifier,
                                hardwareId: HardwareIdentifier,
                                uri: Uri, diff: Option[DiffInfo],
                                ecuIdentifiers: Map[EcuIdentifier, TargetCustomUri])

  final case class Ecu(ecuSerial: EcuIdentifier, device: DeviceId, namespace: Namespace, primary: Boolean,
                       hardwareId: HardwareIdentifier, tufKey: TufKey)

  final case class CurrentImage (namespace: Namespace, ecuSerial: EcuIdentifier, image: Image, attacksDetected: String)

  final case class EcuTarget(namespace: Namespace, version: Int, ecuIdentifier: EcuIdentifier, customImage: CustomImage)

  final case class DeviceUpdateTarget(device: DeviceId, correlationId: Option[CorrelationId], updateId: Option[UpdateId], targetVersion: Int, inFlight: Boolean)

  final case class DeviceCurrentTarget(device: DeviceId, targetVersion: Int)

  final case class FileCache(role: RoleType, version: Int, device: DeviceId, expires: Instant, file: Json)

  final case class FileCacheRequest(namespace: Namespace, targetVersion: Int, device: DeviceId,
                                    status: FileCacheRequestStatus, timestampVersion: Int,
                                    correlationId: Option[CorrelationId] = None)

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

  final case class AutoUpdate(namespace: Namespace, device: DeviceId, ecuSerial: EcuIdentifier, targetName: TargetName)

  final case class TargetsCustom(correlationId: Option[CorrelationId])

}
