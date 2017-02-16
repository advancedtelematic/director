package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.manifest.Verifier
import com.advancedtelematic.director.util.{DefaultPatience,DirectorSpec, ResourceSpec}
import org.genivi.sota.data.{GeneratorOps, Uuid}
import org.scalacheck.Gen

class DeviceResourceSpec extends DirectorSpec with DefaultPatience with ResourceSpec with Requests {
  import GeneratorOps._

  test("Can register device") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)
  }

  test("Can't register device with primary ECU not in `ecus`") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate.filter(_.ecu_serial != primEcu)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceFail(regDev, StatusCodes.BadRequest) shouldBe s"The primary ecu: ${primEcu.get} isn't part of the list of ECUs"
  }

  test("Device can update a registered device") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(device, primEcu, ecuManifests).generate

    updateManifestOk(deviceManifest)
  }

  test("Device must have the ecu given as primary") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
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
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val fakePrimEcu = GenEcuSerial.generate
    val fakePrimCrypto = GenCrypto.generate
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
    def testVerifier(c: Crypto): Verifier.Verifier = c.publicKey match {
      case "REJECT ME" => Verifier.alwaysReject
      case _           => Verifier.alwaysAccept
    }

    val verifyRoutes = routesWithVerifier(testVerifier)

    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val ecusWork = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)
    val ecusFail = Gen.nonEmptyContainerOf[List, EcuSerial](GenEcuSerial).generate.map(ecu => RegisterEcu(ecu, Crypto(GenKeyType.generate, "REJECT ME")))
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
      if (regEcu.crypto.publicKey == "REJECT ME") {
        if (mImages.get(regEcu.ecu_serial).isDefined) {
          println(s"The ecu that failed is: ${regEcu.ecu_serial}")
          println(s"Ecu info: ${ecus.find(_.ecu_serial == regEcu.ecu_serial)}")
          println(s"Manifest: ${ecuManifests.find(_.signed.ecu_serial == regEcu.ecu_serial)}")
        }
        mImages.get(regEcu.ecu_serial) shouldBe None
        } else {
        mImages.get(regEcu.ecu_serial) shouldBe Some(ecuMan.installed_image)
      }
    }
  }
}
