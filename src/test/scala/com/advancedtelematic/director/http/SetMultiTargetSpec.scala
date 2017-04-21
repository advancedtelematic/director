package com.advancedtelematic.director.http

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.AdminRequest.RegisterDevice
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, SetMultiTargets}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}

import scala.async.Async._

class SetMultiTargetSpec extends DirectorSpec
    with AdminRepositorySupport
    with DeviceRepositorySupport
    with DefaultPatience
    with ResourceSpec
    with Requests {

  test("can schedule a multi-target update") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))

    registerDeviceOk(regDev)

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdateRequest(Map(primEcuReg.hardwareId -> targetUpdate))

    val mtuId = createMultiTargetUpdateOK(mtu)

    SetMultiTargets.setMultiUpdateTargets(defaultNs, device, mtuId).futureValue
    val update = adminRepository.fetchTargetVersion(defaultNs, device, 1).futureValue

    update shouldBe Map(primEcu -> CustomImage(targetUpdate.to.image, Uri()))
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
      ecu.ecu_serial -> CustomImage(mtu.to.image, Uri())
    }.toMap

    val f = async {
      await(SetMultiTargets.setMultiUpdateTargets(defaultNs, device, updateId))
      val update = await(adminRepository.fetchTargetVersion(defaultNs, device, 1))
      update shouldBe expected
    }

    f.futureValue
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

    async {
      await(SetMultiTargets.setMultiUpdateTargets(defaultNs, device, mtuId))
      val update = await(adminRepository.fetchTargetVersion(defaultNs, device, 1))
      update shouldBe Map(primEcu -> CustomImage(targetUpdate.to.image, Uri()))
    }.futureValue


    val ecuManifestTarget = Seq(GenSignedEcuManifestWithImage(primEcu, CustomImage(targetUpdate.to.image, Uri()).image).generate)
    val devManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestTarget).generate
    updateManifestOk(device, devManifestTarget)

    async {
      await(deviceRepository.getCurrentVersion(device)) shouldBe 1
    }.futureValue
  }
}
