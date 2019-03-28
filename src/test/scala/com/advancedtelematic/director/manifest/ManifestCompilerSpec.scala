package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.data.AdminDataType.TargetUpdate
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.director.data.Generators._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import io.circe.syntax._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.UptaneDataType._
import com.advancedtelematic.director.data.DbDataType.{Assignment, DeviceKnownStatus, EcuTarget, EcuTargetId}
import com.advancedtelematic.libats.data.DataType.Namespace

class ManifestCompilerSpec extends DirectorSpec {

  implicit class TargetUpdateToImage(value: TargetUpdate) {
    def toImage: Image =
      Image(value.target, FileInfo(Hashes(value.checksum.hash), value.targetLength))
  }

  val ns = Namespace("ns-ManifestCompilerSpec")

  val primary = GenEcuIdentifier.generate
  val secondary = GenEcuIdentifier.generate
  val targetUpdate = GenTargetUpdate.generate
  val deviceId = DeviceId.generate()
  val ecuTarget = EcuTarget(ns, EcuTargetId.generate(), targetUpdate.target, targetUpdate.targetLength, targetUpdate.checksum, targetUpdate.checksum.hash, targetUpdate.uri)
  val assignment = Assignment(ns, deviceId, primary, ecuTarget.id, GenCorrelationId.generate, inFlight = true)

  test("manifest setting already known versions is a NOOP") {
    val ecuManifest = EcuManifest(targetUpdate.toImage, primary, "")
    val secondaryEcuManifest = EcuManifest(targetUpdate.toImage, secondary, "")
    val ecuVersionManifest = Map(primary -> SignedPayload(Seq.empty, ecuManifest, ecuManifest.asJson), secondary -> SignedPayload(Seq.empty, secondaryEcuManifest, secondaryEcuManifest.asJson))

    val manifest = DeviceManifest(primary, ecuVersionManifest)

    val currentStatus = DeviceKnownStatus(deviceId, primary, Map(primary -> Some(ecuTarget.id), secondary -> Some(ecuTarget.id)), Map(ecuTarget.id -> ecuTarget), Set.empty, Set.empty)

    ManifestCompiler(ns, manifest).apply(currentStatus).get shouldBe currentStatus
  }

  test("manifest setting unknown ecu targets creates targets") {
    val ecuManifest = EcuManifest(targetUpdate.toImage, primary, "")

    val ecuVersionManifest = Map(primary -> SignedPayload(Seq.empty, ecuManifest, ecuManifest.asJson))
    val manifest = DeviceManifest(primary, ecuVersionManifest)

    val currentStatus = DeviceKnownStatus(deviceId, primary, Map.empty, Map.empty, Set.empty, Set.empty)

    val newStatus = ManifestCompiler(ns, manifest).apply(currentStatus).get

    newStatus.ecuTargets should have size (1)

    val newTarget = newStatus.ecuTargets.values.head

    newTarget.filename shouldBe targetUpdate.target
    newTarget.length shouldBe targetUpdate.targetLength
    newTarget.checksum shouldBe targetUpdate.checksum
    newTarget.ns shouldBe ns
    newTarget.sha256 shouldBe targetUpdate.checksum.hash
    newTarget.uri shouldBe None
  }

  test("secondary assignment is completed if target is installed") {
    val ecuManifest = EcuManifest(targetUpdate.toImage, primary, "")
    val ecuVersionManifest = Map(secondary -> SignedPayload(Seq.empty, ecuManifest, ecuManifest.asJson))
    val manifest = DeviceManifest(primary, ecuVersionManifest)
    val secondaryAssignment = Assignment(ns, deviceId, secondary, ecuTarget.id, GenCorrelationId.generate, inFlight = true)

    val currentStatus = DeviceKnownStatus(deviceId, primary, Map(primary -> None, secondary -> None), Map(ecuTarget.id -> ecuTarget), Set(assignment, secondaryAssignment), Set.empty)

    val newStatus = ManifestCompiler(ns, manifest).apply(currentStatus).get

    newStatus.currentAssignments shouldBe Set(assignment)
    newStatus.processedAssignments shouldBe Set(secondaryAssignment)
    newStatus.ecuStatus(primary) shouldBe None
    newStatus.ecuStatus(secondary) should contain(ecuTarget.id)
    newStatus.ecuTargets shouldBe currentStatus.ecuTargets
  }

  test("assignment is completed if target is installed") {
    val ecuManifest = EcuManifest(targetUpdate.toImage, primary, "")
    val ecuVersionManifest = Map(primary -> SignedPayload(Seq.empty, ecuManifest, ecuManifest.asJson))
    val manifest = DeviceManifest(primary, ecuVersionManifest)
    val otherAssignment = Assignment(ns, deviceId, secondary, ecuTarget.id, GenCorrelationId.generate, inFlight = true)

    val currentStatus = DeviceKnownStatus(deviceId, primary, Map(primary -> None), Map(ecuTarget.id -> ecuTarget), Set(assignment, otherAssignment), Set.empty)

    val newStatus = ManifestCompiler(ns, manifest).apply(currentStatus).get

    newStatus.currentAssignments shouldBe Set(otherAssignment)
    newStatus.processedAssignments shouldBe Set(assignment)
    newStatus.ecuStatus(primary) should contain(ecuTarget.id)
    newStatus.ecuTargets shouldBe currentStatus.ecuTargets
  }

  test("Ecu.installed_target for device gets updated with new target id if target was not known") {
    val ecuManifest = EcuManifest(targetUpdate.toImage, primary, "")
    val ecuVersionManifest = Map(primary -> SignedPayload(Seq.empty, ecuManifest, ecuManifest.asJson))
    val manifest = DeviceManifest(primary, ecuVersionManifest)
    val currentStatus = DeviceKnownStatus(deviceId, primary, Map.empty, Map.empty, Set.empty, Set.empty)

    val newStatus = ManifestCompiler(ns, manifest).apply(currentStatus).get
    val newTarget = newStatus.ecuTargets.values.head

    newStatus.ecuStatus(primary) should contain(newTarget.id)
  }
}
