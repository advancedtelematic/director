package com.advancedtelematic.director.http

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.AdminRequest.RegisterDevice
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.{EdGenerators, KeyGenerators, RsaGenerators}
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, SetMultiTargets}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId

trait SetMultiTargetSpec extends DirectorSpec
    with KeyGenerators
    with AdminRepositorySupport
    with DeviceRepositorySupport
    with DefaultPatience
    with RouteResourceSpec
    with Requests {

  val setMultiTargets = new SetMultiTargets()

  test("can schedule a multi-target update") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))

    registerDeviceOk(regDev)

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdateRequest(Map(primEcuReg.hardwareId -> targetUpdate))

    val mtuId = createMultiTargetUpdateOK(mtu)

    setMultiTargets.setMultiUpdateTargets(defaultNs, device, mtuId, CorrelationId.from(mtuId)).futureValue
    val update = adminRepository.fetchTargetVersion(defaultNs, device, 1).futureValue

    update shouldBe Map(primEcu -> CustomImage(targetUpdate.to.image, Uri(), None))
  }

  test("can schedule a multi-target update for several devices") {
    val device0 = DeviceId.generate
    val device1 = DeviceId.generate
    val primEcuReg0 = GenRegisterEcu.generate
    val primEcuReg1 = GenRegisterEcu.generate.copy(hardware_identifier = Some(primEcuReg0.hardwareId))
    val primEcu0 = primEcuReg0.ecu_serial
    val primEcu1 = primEcuReg1.ecu_serial

    val regDev0 = RegisterDevice(device0, primEcu0, Seq(primEcuReg0))
    val regDev1 = RegisterDevice(device1, primEcu1, Seq(primEcuReg1))

    registerDeviceOk(regDev0)
    registerDeviceOk(regDev1)

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdateRequest(Map(primEcuReg0.hardwareId -> targetUpdate))

    val mtuId = createMultiTargetUpdateOK(mtu)

    val affected = setMultiTargets.setMultiUpdateTargetsForDevices(
      defaultNs, Seq(device0, device1), mtuId, CorrelationId.from(mtuId)).futureValue

    affected.toSet shouldBe Set(device0, device1)

    val update0 = adminRepository.fetchTargetVersion(defaultNs, device0, 1).futureValue
    update0 shouldBe Map(primEcu0 -> CustomImage(targetUpdate.to.image, Uri(), None))

    val update1 = adminRepository.fetchTargetVersion(defaultNs, device1, 1).futureValue
    update1 shouldBe Map(primEcu1 -> CustomImage(targetUpdate.to.image, Uri(), None))
  }

  test("only ecus that match the hardwareId will be scheduled") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate

    val ecusThatWillUpdate = GenRegisterEcu.listBetween(2,5).generate
    val ecusThatWillNotUpdate = GenRegisterEcu.listBetween(2,5).generate

    val ecus = primEcuReg :: (ecusThatWillUpdate ++ ecusThatWillNotUpdate)

    val regDev = RegisterDevice(device, primEcuReg.ecu_serial, ecus)
    registerDeviceOk(regDev)

    val mtus = ecusThatWillUpdate.map { regEcu =>
      regEcu.hardwareId -> GenTargetUpdateRequest.generate
    }

    val updateId = createMultiTargetUpdateOK(MultiTargetUpdateRequest(mtus.toMap))

    val expected = ecusThatWillUpdate.zip(mtus).map { case (ecu, (hw, mtu)) =>
      ecu.ecu_serial -> CustomImage(mtu.to.image, Uri(), None)
    }.toMap

    setMultiTargets.setMultiUpdateTargets(defaultNs, device, updateId, CorrelationId.from(updateId)).futureValue
    val update = adminRepository.fetchTargetVersion(defaultNs, device, 1).futureValue
    update shouldBe expected
  }

  test("only most-recently targeted ecus will be updated") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate

    val ecusFirst = GenRegisterEcu.listBetween(2,5).generate
    val ecusSecond = GenRegisterEcu.listBetween(2,5).generate

    val ecus = primEcuReg :: (ecusFirst ++ ecusSecond)

    val regDev = RegisterDevice(device, primEcuReg.ecu_serial, ecus)
    registerDeviceOk(regDev)

    {
      val mtus = ecusFirst.map { regEcu =>
        regEcu.hardwareId -> GenTargetUpdateRequest.generate
      }

      val updateId = createMultiTargetUpdateOK(MultiTargetUpdateRequest(mtus.toMap))

      val expected = ecusFirst.zip(mtus).map { case (ecu, (hw, mtu)) =>
        ecu.ecu_serial -> CustomImage(mtu.to.image, Uri(), None)
      }.toMap

      setMultiTargets.setMultiUpdateTargets(defaultNs, device, updateId, CorrelationId.from(updateId)).futureValue
      val update = adminRepository.fetchTargetVersion(defaultNs, device, 1).futureValue
      update shouldBe expected
    }

    {
      val mtus = ecusSecond.map { regEcu =>
        regEcu.hardwareId -> GenTargetUpdateRequest.generate
      }
      val updateId = createMultiTargetUpdateOK(MultiTargetUpdateRequest(mtus.toMap))
      val expected = ecusSecond.zip(mtus).map { case (ecu, (hw, mtu)) =>
        ecu.ecu_serial -> CustomImage(mtu.to.image, Uri(), None)
      }.toMap

      setMultiTargets.setMultiUpdateTargets(defaultNs, device, updateId, CorrelationId.from(updateId)).futureValue
      val update = adminRepository.fetchTargetVersion(defaultNs, device, 2).futureValue
      update shouldBe expected
    }
  }

  test("can succesfully update a multi-target update") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))

    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate
    updateManifestOk(device, devManifest)

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdateRequest(Map(primEcuReg.hardwareId -> targetUpdate))

    val mtuId = createMultiTargetUpdateOK(mtu)

    setMultiTargets.setMultiUpdateTargets(defaultNs, device, mtuId, CorrelationId.from(mtuId)).futureValue
    val update = adminRepository.fetchTargetVersion(defaultNs, device, 1).futureValue
    update shouldBe Map(primEcu -> CustomImage(targetUpdate.to.image, Uri(), None))

    val ecuManifestTarget = Seq(GenSignedEcuManifestWithImage(primEcu, targetUpdate.to.image).generate)
    val devManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestTarget).generate
    updateManifestOk(device, devManifestTarget)

    deviceRepository.getCurrentVersion(device).futureValue shouldBe 1
  }
}

class RsaSetMultiTargetSpec extends SetMultiTargetSpec with RsaGenerators

class EdSetMultiTargetSpec extends SetMultiTargetSpec with EdGenerators
