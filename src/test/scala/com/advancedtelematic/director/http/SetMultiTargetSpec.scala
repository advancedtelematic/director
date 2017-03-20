package com.advancedtelematic.director.http

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.AdminRequest.RegisterDevice
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, SetMultiTargets}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.data.Namespace

import scala.async.Async._

class SetMultiTargetSpec extends DirectorSpec
    with AdminRepositorySupport
    with DeviceRepositorySupport
    with DefaultPatience
    with ResourceSpec
    with Requests {

  def customImage(mtu: MultiTargetUpdateRequest, namespace: Namespace): CustomImage =
    CustomImage(MultiTargetUpdate(mtu, namespace).image, Uri())

  test("can schedule a multi-target update") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))

    registerDeviceOk(regDev)

    val mtu = GenMultiTargetUpdateRequest.generate.copy(hardwareId = primEcuReg.hardware_identifier)

    createMultiTargetUpdateOK(mtu)

    val f = async {
      await(SetMultiTargets.setMultiUpdateTargets(defaultNs, device, mtu.id))
      val update = await(adminRepository.fetchTargetVersion(defaultNs, device, 1))
      update shouldBe Map(primEcu -> customImage(mtu, defaultNs))
    }

    f.futureValue
  }

  test("only ecus that match the hardwareId will be scheduled") {
    val device = DeviceId.generate
    val primEcuReg = GenRegisterEcu.generate

    val ecusThatWillUpdate = GenRegisterEcu.listBetween(2,5).generate
    val ecusThatWillNotUpdate = GenRegisterEcu.listBetween(2,5).generate

    val ecus = primEcuReg :: (ecusThatWillUpdate ++ ecusThatWillNotUpdate)

    val regDev = RegisterDevice(device, primEcuReg.ecu_serial, ecus)
    registerDeviceOk(regDev)

    val updateId = UpdateId.generate
    val mtus = ecusThatWillUpdate.map { regEcu =>
      GenMultiTargetUpdateRequest.generate.copy(hardwareId = regEcu.hardware_identifier,
                                                id = updateId)
    }

    mtus.foreach { mtu =>
      createMultiTargetUpdateOK(mtu)
    }

    val expected = ecusThatWillUpdate.zip(mtus).map { case (ecu, mtu) =>
      ecu.ecu_serial -> customImage(mtu, defaultNs)
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

    val mtu = GenMultiTargetUpdateRequest.generate.copy(hardwareId = primEcuReg.hardware_identifier)

    createMultiTargetUpdateOK(mtu)

    async {
      await(SetMultiTargets.setMultiUpdateTargets(defaultNs, device, mtu.id))
      val update = await(adminRepository.fetchTargetVersion(defaultNs, device, 1))
      update shouldBe Map(primEcu -> customImage(mtu, defaultNs))
    }.futureValue


    val ecuManifestTarget = Seq(GenSignedEcuManifestWithImage(primEcu, customImage(mtu, defaultNs).image).generate)
    val devManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestTarget).generate
    updateManifestOk(device, devManifestTarget)

    async {
      await(deviceRepository.getCurrentVersion(device)) shouldBe 1
    }.futureValue
  }
}
