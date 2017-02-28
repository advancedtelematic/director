package com.advancedtelematic.director.http

import java.util.concurrent.ConcurrentHashMap
import java.security.PublicKey

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.manifest.Verifier
import com.advancedtelematic.director.util.{DefaultPatience,DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.data.Codecs.encoderEcuManifest
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import org.scalacheck.Gen

class DeviceResourceSpec extends DirectorSpec with DefaultPatience with ResourceSpec with Requests {
  test("Can register device") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)
  }

  test("Can't register device with primary ECU not in `ecus`") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate.filter(_.ecu_serial != primEcu)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceExpected(regDev, StatusCodes.BadRequest)
  }

  test("Device can update a registered device") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(device, primEcu, ecuManifests).generate

    updateManifestOk(deviceManifest)
  }

  test("Device must have the ecu given as primary") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val fakePrimEcu = GenEcuSerial.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++
      (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(device, fakePrimEcu, ecuManifests).generate

    updateManifestExpect(deviceManifest, StatusCodes.NotFound)
  }

  test("Device need to have the correct primary") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val fakePrimEcu = GenEcuSerial.generate
    val fakePrimCrypto = GenClientKey.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++
      (RegisterEcu(primEcu, primCrypto) ::
       RegisterEcu(fakePrimEcu, fakePrimCrypto) ::
       Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(device, fakePrimEcu, ecuManifests).generate

    updateManifestExpect(deviceManifest, StatusCodes.BadRequest)
  }

  test("Device update will only update correct ecus") {
    val taintedKeys = new ConcurrentHashMap[PublicKey, Unit]() // this is like a set
    def testVerifier(c: ClientKey): Verifier.Verifier =
      if (taintedKeys.contains(c.keyval)) {
        Verifier.alwaysReject
      } else {
        Verifier.alwaysAccept
      }

    val verifyRoutes = routesWithVerifier(testVerifier)


    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val ecusWork = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)
    val ecusFail = Gen.nonEmptyContainerOf[List, EcuSerial](GenEcuSerial).generate.map{ecu =>
      val clientKey = GenClientKey.generate
      taintedKeys.put(clientKey.keyval, Unit)
      RegisterEcu(ecu, clientKey)
    }
    val ecus = ecusWork ++ ecusFail

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOkWith(regDev, verifyRoutes)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(device, primEcu, ecuManifests).generate

    updateManifestOkWith(deviceManifest, verifyRoutes)

    val images = getInstalledImagesOkWith(device, verifyRoutes)

    val mImages = {
      val start = images.groupBy(_._1).mapValues(_.map(_._2))
      start.values.foreach { x =>
        x.length shouldBe 1
      }

      start.mapValues(_.head)
    }

    ecus.zip(ecuManifests.map(_.signed)).foreach { case (regEcu, ecuMan) =>
      if (regEcu.clientKey.keyval.getFormat() == "REJECT ME") {
        mImages.get(regEcu.ecu_serial) shouldBe None
        } else {
        mImages.get(regEcu.ecu_serial) shouldBe Some(ecuMan.installed_image)
      }
    }
  }

  test("Can set target for device") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val ecus = List(RegisterEcu(primEcu, primCrypto))

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val targets = SetTarget(Map(primEcu -> GenCustomImage.generate))

    setTargetsOk(device, targets)
  }

  test("Device can update to set target") {
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenClientKey.generate
    val ecus = List(RegisterEcu(primEcu, primCrypto))

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> targetImage))

    setTargetsOk(device, targets)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(device, primEcu, ecuManifests).generate

    updateManifestOk(deviceManifest)

    val ecuManifestsTarget = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }.map { sig =>
      sig.copy(signed = sig.signed.copy(installed_image = targetImage.image))
    }
    val deviceManifestTarget = GenSignedDeviceManifest(device, primEcu, ecuManifestsTarget).generate

    updateManifestOk(deviceManifestTarget)
  }

}
