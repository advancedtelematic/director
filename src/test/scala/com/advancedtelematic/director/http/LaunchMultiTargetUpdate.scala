package com.advancedtelematic.director.http

import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.FileCacheDB
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.HardwareIdentifier
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._
import eu.timepit.refined.api.Refined
import io.circe.syntax._

class LaunchMultiTargetUpdate extends DirectorSpec with DefaultPatience with FileCacheDB with ResourceSpec with NamespacedRequests {
  val ato: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("a"))
  val bto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("b"))
  val cto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("c"))
  val dto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("d"))
  val ahw: HardwareIdentifier = Refined.unsafeApply("a")
  val bhw: HardwareIdentifier = Refined.unsafeApply("b")
  val chw: HardwareIdentifier = Refined.unsafeApply("c")
  val dhw: HardwareIdentifier = Refined.unsafeApply("d")

  def sendManifest(device: DeviceId, primEcu: EcuSerial)(hwimages: (EcuSerial, TargetUpdate)*)(implicit ns: NamespaceTag): Unit = {
    val ecuManifest = hwimages.map {case (ecu, target) =>
      val sig = GenSignedEcuManifest(ecu).generate

      sig.copy(signed = sig.signed.copy(installed_image = target.image))
    }

    updateManifestOk(device, GenSignedDeviceManifest(primEcu, ecuManifest).generate)
  }

  def sendManifestCustom(device: DeviceId, primEcu: EcuSerial, target: TargetUpdate, custom: CustomManifest)(implicit ns: NamespaceTag): Unit = {
    val ecuManifest = Seq {
      val sig = GenSignedEcuManifest(primEcu).generate
      sig.copy(signed = sig.signed.copy(installed_image = target.image, custom = Some(custom.asJson)))
    }

    updateManifestOk(device, GenSignedDeviceManifest(primEcu, ecuManifest).generate)
  }

  def registerDevice(hardwares : HardwareIdentifier*)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val device = DeviceId.generate

    val regEcus = hardwares.map { hw =>
      GenRegisterEcu.generate.copy(hardware_identifier = Some(hw))
    }
    val primEcu = regEcus.head.ecu_serial

    registerDeviceOk(RegisterDevice(device, primEcu, regEcus))

    (device, primEcu, regEcus.map(_.ecu_serial))
  }

  def registerNSDeviceOk(hwimages: (HardwareIdentifier, TargetUpdate)*)(implicit ns: NamespaceTag): DeviceId = {
    val (device, primEcu, ecus) = registerDevice(hwimages.map(_._1) : _*)

    val manifest = ecus.zip(hwimages.map(_._2))

    sendManifest(device, primEcu)(manifest :_*)

    device
  }

  def createMtu(hwimages: (HardwareIdentifier, TargetUpdate)*)(implicit ns: NamespaceTag): UpdateId = {
    val mtu = MultiTargetUpdateRequest(hwimages.toMap.mapValues(target => TargetUpdateRequest(None, target, OSTREE, false)))
    createMultiTargetUpdateOK(mtu)
  }

  def createMtuWithFrom(hwimages: (HardwareIdentifier, (TargetUpdate, TargetUpdate))*)(implicit ns: NamespaceTag): UpdateId = {
    val mtu = MultiTargetUpdateRequest(hwimages.toMap.mapValues{ case (from,target) => TargetUpdateRequest(Some(from), target, OSTREE, false)})
    createMultiTargetUpdateOK(mtu)
  }

  testWithNamespace("multi_target_updates/affected Can get devices that match mtu") { implicit ns =>
    val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
    val device2 = registerNSDeviceOk(ahw -> ato, chw -> cto)
    val device3 = registerNSDeviceOk(dhw -> dto)

    val update = createMtu(ahw -> ato)
    val affected = findAffectedByUpdate(update, Seq(device1, device2, device3))

    affected.toSet.size shouldBe affected.size
    affected.toSet shouldBe Set(device1, device2)
  }

  testWithNamespace("multi_target_updates/affected mtu with multiple updates") { implicit ns =>
    val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
    val device2 = registerNSDeviceOk(ahw -> ato, chw -> cto)
    val device3 = registerNSDeviceOk(dhw -> dto)

    val update = createMtu(ahw -> ato, bhw -> bto)
    val affected = findAffectedByUpdate(update, Seq(device1, device2, device3))

    affected.toSet.size shouldBe affected.size
    affected.toSet shouldBe Set(device1)
  }

  testWithNamespace("multi_target_updates/affected mtu with from field") { implicit ns =>
    val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
    val device2 = registerNSDeviceOk(ahw -> bto, chw -> cto)
    val device3 = registerNSDeviceOk(dhw -> dto)

    val update = createMtuWithFrom(ahw -> (ato -> dto))
    val affected = findAffectedByUpdate(update, Seq(device1, device2, device3))

    affected.toSet.size shouldBe affected.size
    affected.toSet shouldBe Set(device1)
  }

  testWithNamespace("multi_target_updates/affected mtu ignores devices in a campaign") { implicit ns =>
    val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
    val device2 = registerNSDeviceOk(ahw -> ato, chw -> cto)
    val device3 = registerNSDeviceOk(dhw -> dto)

    val update = createMtu(ahw -> ato)

    val launched = launchMtu(update, Seq(device1))
    launched shouldBe Seq(device1)

    val launched2 = launchMtu(update, Seq(device1))
    launched2 shouldBe Seq()

    val affected = findAffectedByUpdate(update, Seq(device1, device2, device3))

    affected shouldBe Seq(device2)
  }

  test("mutli_target_upates update success") {
    withRandomNamespace { implicit ns =>
      val (device, prim, ecu) = registerDevice(ahw)
      sendManifest(device, prim)(prim -> ato)

      val update = createMtu(ahw -> bto)
      val launched = launchMtu(update, Seq(device))

      sendManifestCustom(device, prim, bto, CustomManifest(OperationResult("hh", 0, "okay")))
    }
  }

  test("mutli_target_upates update failed") {
    withRandomNamespace { implicit ns =>
      val (device, prim, ecu) = registerDevice(ahw)
      sendManifest(device, prim)(prim -> ato)

      val update = createMtu(ahw -> bto)
      val launched = launchMtu(update, Seq(device))

      sendManifestCustom(device, prim, bto, CustomManifest(OperationResult("hh", 19, "error")))
    }
  }

  test("mutli_target_upates update device reports wrong") {
    withRandomNamespace { implicit ns =>
      val (device, prim, ecu) = registerDevice(ahw)
      sendManifest(device, prim)(prim -> ato)

      val update = createMtu(ahw -> cto)
      val launched = launchMtu(update, Seq(device))

      sendManifestCustom(device, prim, bto, CustomManifest(OperationResult("hh", 0, "okay, but wrong report")))
    }
  }
}
