package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.KeyGenerators
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheDB, SetTargets}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, NamespaceTag, RouteResourceSpec}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType
import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod.SignatureMethod
import org.scalatest.{BeforeAndAfterAll, Inspectors}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod
import cats.syntax.show._
import com.advancedtelematic.director.http.DeviceDebugInfo.DeviceDebugResult
import DeviceDebugInfo._
import com.advancedtelematic.director.util.NamespaceTag.NamespaceTag
import com.advancedtelematic.libats.data.DataType.Namespace
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libtuf.crypt.CanonicalJson._

class DeviceDebugInfoInfoResourceSpec extends DirectorSpec with KeyGenerators with DefaultPatience with DeviceRepositorySupport
  with FileCacheDB with RouteResourceSpec with NamespacedRequests with Inspectors with BeforeAndAfterAll {

  override val testKeyType: TufDataType.KeyType = TufDataType.KeyType.default
  override val testSignatureMethod: SignatureMethod = SignatureMethod.ED25519

  testWithNamespace("returns device debug information") { implicit ns =>
    createRepoOk(testKeyType)

    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = GenRegisterEcu.atMost(5).generate ++ (primEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)
    updateManifestOk(device, deviceManifest)

    fetchTargetsFor(device)

    Get(s"/admin/device/${device.show}") ~> routes ~> check  {
      status shouldBe StatusCodes.OK

      val deviceDebug = responseAs[DeviceDebugResult]

      deviceDebug.deviceId shouldBe device
      deviceDebug.ecus.map(_.id) should contain theSameElementsAs ecus.map(_.ecu_serial)

      deviceDebug.manifests should have size(1)
      deviceDebug.manifests.headOption.map(_.success) should contain(true)
      deviceDebug.manifests.map(_.payload.canonical).headOption should contain(deviceManifest.json.canonical)

      deviceDebug.targets shouldNot be(empty)
    }
  }
}
