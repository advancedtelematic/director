package com.advancedtelematic.director.manifest

import cats.data.ValidatedNel
import com.advancedtelematic.director.data.UptaneDataType.Image
import com.advancedtelematic.director.data.DbDataType.{Assignment, DeviceKnownStatus, EcuTarget, EcuTargetId}
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.http.Errors
import com.advancedtelematic.libats.data.DataType.{Checksum, HashMethod, Namespace}
import com.advancedtelematic.libats.data.EcuIdentifier
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object ManifestCompiler {
  private val _log = LoggerFactory.getLogger(this.getClass)

  private def assignmentExists(assignments: Set[Assignment],
                               ecuTargets: Map[EcuTargetId, EcuTarget],
                               ecuIdentifier: EcuIdentifier, ecuManifest: EcuManifest): Option[Assignment] = {
    assignments.find { assignment =>
      val installedPath = ecuManifest.installed_image.filepath
      val installedChecksum = ecuManifest.installed_image.fileinfo.hashes.sha256

      _log.debug(s"looking for ${assignment.ecuTargetId} in $ecuTargets")

      val assignmentTarget = ecuTargets(assignment.ecuTargetId)

      assignment.ecuId == ecuIdentifier &&
        assignmentTarget.filename == installedPath &&
        assignmentTarget.checksum.hash == installedChecksum
    }
  }

  def apply(ns: Namespace, manifest: DeviceManifest): DeviceKnownStatus => Try[DeviceKnownStatus] = {
    validateManifest(ns, manifest, _).map { compileManifest(ns, manifest) }
  }

  private def validateManifest(ns: Namespace, manifest: DeviceManifest, deviceKnownStatus: DeviceKnownStatus): Try[DeviceKnownStatus] = {
    if(manifest.primary_ecu_serial != deviceKnownStatus.primaryEcu)
      Failure(Errors.Manifest.EcuNotPrimary)
    else
      Success(deviceKnownStatus)
  }

  private def compileManifest(ns: Namespace, manifest: DeviceManifest): DeviceKnownStatus => DeviceKnownStatus = (knownStatus: DeviceKnownStatus) => {
    _log.debug(s"CURRENT status for device: $knownStatus")

    val assignmentsProcessedInManifest = manifest.ecu_version_manifests.flatMap { case (ecuId, signedManifest) =>
      assignmentExists(knownStatus.currentAssignments, knownStatus.ecuTargets, ecuId, signedManifest.signed)
    }

    val newProcessed = knownStatus.processedAssignments ++ assignmentsProcessedInManifest
    val newAssigned = knownStatus.currentAssignments -- assignmentsProcessedInManifest

    val newEcuTargets = manifest.ecu_version_manifests.values.map { ecuManifest =>
      val installedImage = ecuManifest.signed.installed_image
      val existingEcuTarget = findEcuTargetByImage(knownStatus.ecuTargets, installedImage)

      if(existingEcuTarget.isEmpty)
        EcuTarget(ns, EcuTargetId.generate, installedImage.filepath, installedImage.fileinfo.length, Checksum(HashMethod.SHA256, installedImage.fileinfo.hashes.sha256),installedImage.fileinfo.hashes.sha256, uri = None)
      else
        existingEcuTarget.get

    }.map(e => e.id -> e).toMap

    val statusInManifest = manifest.ecu_version_manifests.mapValues { ecuManifest =>
      val newTargetO = findEcuTargetByImage(newEcuTargets, ecuManifest.signed.installed_image)
      newTargetO.map(_.id)
    }.filter(_._2.isDefined)

    DeviceKnownStatus(
      knownStatus.deviceId,
      knownStatus.primaryEcu,
      knownStatus.ecuStatus ++ statusInManifest,
      knownStatus.ecuTargets ++ newEcuTargets,
      newAssigned,
      newProcessed)
  }

  private def findEcuTargetByImage(ecuTargets: Map[EcuTargetId, EcuTarget], image: Image): Option[EcuTarget] = {
    ecuTargets.values.find { ecuTarget =>
      val imagePath = image.filepath
      val imageChecksum = image.fileinfo.hashes.sha256

      ecuTarget.filename == imagePath && ecuTarget.checksum.hash == imageChecksum
    }
  }
}
