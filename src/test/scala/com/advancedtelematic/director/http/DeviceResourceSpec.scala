package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.manifest.Verify
import com.advancedtelematic.director.util.{DefaultPatience,DirectorSpec, ResourceSpec}
import org.genivi.sota.data.{GeneratorOps, Uuid}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.scalacheck.Gen

class DeviceResourceSpec extends DirectorSpec with DefaultPatience with ResourceSpec {
  import GeneratorOps._
  import org.genivi.sota.marshalling.CirceInstances._

  test("Can register device") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    Post(apiUri("device"), regDev) ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }
  }

  test("Can't register device with primary ECU not in `ecus`") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate.filter(_.ecu_serial != primEcu)

    val regDev = RegisterDevice(device, primEcu, ecus)

    Post(apiUri("device"), regDev) ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] shouldBe s"The primary ecu: ${primEcu.get} isn't part of the list of ECUs"
    }
  }

  test("Device can update a registered device") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    Post(apiUri("device"), regDev) ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val ecuManifests = ecus.map { regEcu => GenSigned(GenEcuManifest(regEcu.ecu_serial)).generate }

    val deviceManifest = GenSignedValue(DeviceManifest(device, primEcu, ecuManifests)).generate

    Put(apiUri("mydevice/manifest"), deviceManifest) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  test("Device must have the ecu given as primary") {
    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val fakePrimEcu = GenEcuSerial.generate
    val ecus = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++
      (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    Post(apiUri("device"), regDev) ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val ecuManifests = ecus.map { regEcu => GenSigned(GenEcuManifest(regEcu.ecu_serial)).generate }

    val deviceManifest = GenSignedValue(DeviceManifest(device, fakePrimEcu, ecuManifests)).generate

    Put(apiUri("mydevice/manifest"), deviceManifest) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
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

    Post(apiUri("device"), regDev) ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val ecuManifests = ecus.map { regEcu => GenSigned(GenEcuManifest(regEcu.ecu_serial)).generate }

    val deviceManifest = GenSignedValue(DeviceManifest(device, fakePrimEcu, ecuManifests)).generate

    Put(apiUri("mydevice/manifest"), deviceManifest) ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  test("Device update will only update correct ecus") {
    def testVerifier(c: Crypto): Verify.Verifier = c.publicKey match {
      case "REJECT ME" => Verify.alwaysReject
      case _           => Verify.alwaysAccept
    }

    val verifyRoutes = routesWithVerifier(testVerifier)

    val device = Uuid.generate()
    val primEcu = GenEcuSerial.generate
    val primCrypto = GenCrypto.generate
    val ecusWork = Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate ++ (RegisterEcu(primEcu, primCrypto) :: Gen.containerOf[List, RegisterEcu](GenRegisterEcu).generate)
    val ecusFail = Gen.nonEmptyContainerOf[List, EcuSerial](GenEcuSerial).generate.map(ecu => RegisterEcu(ecu, Crypto(GenSignatureMethod.generate, "REJECT ME")))
    val ecus = ecusWork ++ ecusFail

    val regDev = RegisterDevice(device, primEcu, ecus)

    Post(apiUri("device"), regDev) ~> verifyRoutes ~> check {
      status shouldBe StatusCodes.Created
    }

    val ecuManifests = ecus.map { regEcu => GenSigned(GenEcuManifest(regEcu.ecu_serial)).generate }

    val deviceManifest = GenSignedValue(DeviceManifest(device, primEcu, ecuManifests)).generate

    Put(apiUri("mydevice/manifest"), deviceManifest) ~> verifyRoutes ~> check {
      status shouldBe StatusCodes.OK
    }

    Get(apiUri(s"device/${device.show}/installed_images")) ~> verifyRoutes ~> check {
      status shouldBe StatusCodes.OK

      val mImages = {
        val images = responseAs[Seq[(EcuSerial, Image)]]
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
}
