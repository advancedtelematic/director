package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined

class LaunchMultiTargetUpdate extends DirectorSpec with DefaultPatience with ResourceSpec with Requests {

  val ato: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("a"))
  val bto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("b"))
  val cto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("c"))
  val dto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("d"))
  val ahw: HardwareIdentifier = Refined.unsafeApply("a")
  val bhw: HardwareIdentifier = Refined.unsafeApply("b")
  val chw: HardwareIdentifier = Refined.unsafeApply("c")
  val dhw: HardwareIdentifier = Refined.unsafeApply("d")

  def registerNSDeviceOk(hwimages: (HardwareIdentifier, TargetUpdate)*)(implicit ns: NamespaceTag): DeviceId = {
    val device = DeviceId.generate

    val regEcus = hwimages.map { case (hw, _) =>
      GenRegisterEcu.generate.copy(hardware_identifier = Some(hw))
    }

    val primEcu = regEcus.head.ecu_serial

    registerDevice(RegisterDevice(device, primEcu, regEcus)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val ecuManifest = hwimages.zip(regEcus.map(_.ecu_serial)).map {case ((hw, target), ecu) =>
      val sig = GenSignedEcuManifest(ecu).generate
      sig.copy(signed = sig.signed.copy(installed_image = target.image))
    }

    updateManifest(device, GenSignedDeviceManifest(primEcu, ecuManifest).generate).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    device
  }

  def createMtu(hwimages: (HardwareIdentifier, TargetUpdate)*)(implicit ns: NamespaceTag): UpdateId = {
    val mtu = MultiTargetUpdateRequest(hwimages.toMap.mapValues(target => TargetUpdateRequest(None, target)))
    Post(apiUri(s"multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }
  }

  def createMtuWithFrom(hwimages: (HardwareIdentifier, (TargetUpdate, TargetUpdate))*)(implicit ns: NamespaceTag): UpdateId = {
    val mtu = MultiTargetUpdateRequest(hwimages.toMap.mapValues{ case (from,target) => TargetUpdateRequest(Some(from), target)})
    Post(apiUri(s"multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }
  }

  def findAffected(updateId: UpdateId, devices: Seq[DeviceId])(implicit ns: NamespaceTag): Seq[DeviceId] =
    Get(apiUri(s"admin/multi_target_updates/${updateId.show}/affected"), devices).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]]
    }

  def launchMtu(updateId: UpdateId, devices: Seq[DeviceId])(implicit ns: NamespaceTag): Seq[DeviceId] =
    Put(apiUri(s"admin/multi_target_updates/${updateId.show}"), devices).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]]
    }

  test("multi_target_updates/affected Can get devices that match mtu") {
    withRandomNamespace {implicit ns =>
      val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
      val device2 = registerNSDeviceOk(ahw -> ato, chw -> cto)
      val device3 = registerNSDeviceOk(dhw -> dto)

      val update = createMtu(ahw -> ato)
      val affected = findAffected(update, Seq(device1, device2, device3))

      affected.toSet.size shouldBe affected.size
      affected.toSet shouldBe Set(device1, device2)
    }
  }

  test("multi_target_updates/affected mtu with multiple updates") {
    withRandomNamespace {implicit ns =>
      val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
      val device2 = registerNSDeviceOk(ahw -> ato, chw -> cto)
      val device3 = registerNSDeviceOk(dhw -> dto)

      val update = createMtu(ahw -> ato, bhw -> bto)
      val affected = findAffected(update, Seq(device1, device2, device3))

      affected.toSet.size shouldBe affected.size
      affected.toSet shouldBe Set(device1)
    }
  }

  test("multi_target_updates/affected mtu with from field") {
    withRandomNamespace {implicit ns =>
      val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
      val device2 = registerNSDeviceOk(ahw -> bto, chw -> cto)
      val device3 = registerNSDeviceOk(dhw -> dto)

      val update = createMtuWithFrom(ahw -> (ato -> dto))
      val affected = findAffected(update, Seq(device1, device2, device3))

      affected.toSet.size shouldBe affected.size
      affected.toSet shouldBe Set(device1)
    }
  }

  test("multi_target_updates/affected mtu ignores devices in a campaign") {
    withRandomNamespace {implicit ns =>
      val device1 = registerNSDeviceOk(ahw -> ato, bhw -> bto)
      val device2 = registerNSDeviceOk(ahw -> ato, chw -> cto)
      val device3 = registerNSDeviceOk(dhw -> dto)

      val update = createMtu(ahw -> ato)

      val launched = launchMtu(update, Seq(device1))
      launched shouldBe Seq(device1)

      val launched2 = launchMtu(update, Seq(device1))
      launched2 shouldBe Seq()

      val affected = findAffected(update, Seq(device1, device2, device3))

      affected shouldBe Seq(device2)
    }
  }
}
