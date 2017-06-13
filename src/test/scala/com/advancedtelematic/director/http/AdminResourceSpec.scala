package com.advancedtelematic.director.http

import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs.encoderEcuManifest
import com.advancedtelematic.director.data.DataType.CustomImage
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{FileCacheDB, SetVersion}
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, TargetFilename}
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined
import io.circe.Json
import io.circe.syntax._

class AdminResourceSpec extends DirectorSpec with FileCacheDB with ResourceSpec with Requests with SetVersion {
  def registerDeviceOk(ecus: Int)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val device = DeviceId.generate

    val ecuSerials = GenEcuSerial.listBetween(ecus, ecus).generate
    val primEcu = ecuSerials.head

    val regEcus = ecuSerials.map{ ecu => GenRegisterEcu.generate.copy(ecu_serial = ecu)}
    val regDev = RegisterDevice(device, primEcu, regEcus)

    registerDevice(regDev).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    (device, primEcu, ecuSerials)
  }

  def updateManifestOk(device: DeviceId, primEcu: EcuSerial, ecus: Map[EcuSerial, TargetFilename])
                      (implicit ns: NamespaceTag): Unit = {
    val ecuManifests = ecus.keys.toSeq.map { ecu =>
      val sig = GenSignedEcuManifest(ecu).generate
      val newImage = sig.signed.installed_image.copy(filepath = ecus(ecu))
      sig.copy(signed = sig.signed.copy(installed_image = newImage))
    }

    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifest(device, devManifest).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  def createDeviceWithImages(images: TargetFilename*)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val (device, primEcu, ecuSerials) = registerDeviceOk(images.length)
    val ecus = ecuSerials.zip(images).toMap

    updateManifestOk(device, primEcu, ecus)

    (device, primEcu, ecuSerials)
  }

  def registerNSDeviceOk(images: TargetFilename*)(implicit ns: NamespaceTag): DeviceId = createDeviceWithImages(images : _*)._1

  def getAffected(filepath: String) (limit: Option[Long]= None, offset: Option[Long] = None)
                 (implicit ns: NamespaceTag): PaginationResult[DeviceId] = {
    val query = Uri.Query(limit.map("limit" -> _.toString).toMap ++ offset.map("offset" -> _.toString).toMap)
    val entity = Json.obj("filepath" -> filepath.asJson)

    Get(Uri(apiUri(s"admin/images/affected")).withQuery(query), entity).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val pag = responseAs[PaginationResult[DeviceId]]
      pag.limit shouldBe limit.getOrElse(50L)
      pag.offset shouldBe offset.getOrElse(0L)
      (pag.values.length <= pag.limit) shouldBe true
      pag.values.length shouldBe scala.math.max(0, scala.math.min(pag.total - pag.offset, pag.limit))
      pag
    }
  }

  def findDevice(device: DeviceId)(implicit ns: NamespaceTag): Seq[EcuInfoResponse] = {
    import com.advancedtelematic.director.data.Codecs._
    Get(apiUri(s"admin/devices/${device.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[EcuInfoResponse]]
    }
  }

  def findPublicKey(device: DeviceId, ecuSerial: EcuSerial)(implicit ns: NamespaceTag): ClientKey = {
    import com.advancedtelematic.libtuf.data.ClientCodecs._
    Get(Uri(apiUri(s"admin/devices/${device.show}/ecus/public_key"))
          .withQuery(Uri.Query("ecu_serial" -> ecuSerial.value))).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ClientKey]
    }
  }

  def setRandomTargets(device: DeviceId, ecuSerials: Seq[EcuSerial])(implicit ns: NamespaceTag): Map[EcuSerial, CustomImage] = {
    val targets = ecuSerials.map{ ecu =>
      ecu -> GenCustomImage.generate
    }.toMap

    setTargets(device, SetTarget(targets)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    pretendToGenerate().futureValue

    targets
  }

  val afn: TargetFilename = Refined.unsafeApply("a")
  val bfn: TargetFilename = Refined.unsafeApply("b")
  val cfn: TargetFilename = Refined.unsafeApply("c")
  val dfn: TargetFilename = Refined.unsafeApply("d")
  test("images/affected Can get devices with an installed image filename") {
    withNamespace("ns get several") {implicit ns =>
      val device1 = registerNSDeviceOk(afn, bfn)
      val device2 = registerNSDeviceOk(afn, cfn)
      val device3 = registerNSDeviceOk(dfn)

      val pag = getAffected("a")()
      pag.total shouldBe 2
      pag.values.toSet shouldBe Set(device1, device2)
    }
  }

  test("images/affected Don't count devices multiple times") {
    withNamespace("ns no dups") { implicit ns =>
      val device = registerNSDeviceOk(afn, afn, cfn)

      val pag = getAffected("a")()
      pag.total shouldBe 1
      pag.values shouldBe Seq(device)
    }
  }

  test("images/affected Pagination works") {
    withNamespace("ns with limit/offset") { implicit ns =>
      val device1 = registerNSDeviceOk(afn, bfn)
      val device2 = registerNSDeviceOk(afn, cfn)
      val device3 = registerNSDeviceOk(afn)

      val pag1 = getAffected("a")(limit = Some(2))
      val pag2 = getAffected("a")(offset = Some(2))

      pag1.total shouldBe 3
      pag2.total shouldBe 3

      (pag1.values ++ pag2.values).length shouldBe 3
      (pag1.values ++ pag2.values).toSet shouldBe Set(device1, device2, device3)
    }
  }

  test("images/affected Ignores devices in a campaign") {
    withNamespace("ns with campaign") { implicit ns =>
      val device1 = registerNSDeviceOk(afn, bfn)
      val device2 = registerNSDeviceOk(afn)

      setCampaign(device1, 1).futureValue
      pretendToGenerate.futureValue

      val pag = getAffected("a")()
      pag.total shouldBe 1
      pag.values shouldBe Seq(device2)
    }
  }

  test("images/affected Includes devices that are at the latest target") {
    withNamespace("ns with campaign, at latest") {implicit ns =>
      val device1 = registerNSDeviceOk(afn, bfn)
      val device2 = registerNSDeviceOk(afn)

      setCampaign(device1, 1).futureValue
      setDeviceVersion(device1, 1).futureValue

      val pag = getAffected("a")()
      pag.total shouldBe 2
      pag.values.toSet shouldBe Set(device1, device2)
    }
  }

  test("devices/id/ecus/public_key can get public key") {
    withNamespace("public key") { implicit ns =>
      val device = DeviceId.generate

      val ecuSerials = GenEcuSerial.listBetween(2, 5).generate
      val primEcu = ecuSerials.head

      val regEcus = ecuSerials.map{ ecu => GenRegisterEcu.generate.copy(ecu_serial = ecu)}
      val regDev = RegisterDevice(device, primEcu, regEcus)

      registerDevice(regDev).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }

      regEcus.foreach { regEcu =>
        findPublicKey(device, regEcu.ecu_serial) shouldBe regEcu.clientKey
      }
    }
  }

  test("devices/id gives a list of ecuresponses") {
    withNamespace("check ecuresponse") {implicit ns =>
      val images = Seq(afn, bfn)
      val (device, primEcu, ecusSerials) = createDeviceWithImages(images : _*)
      val ecus = ecusSerials.zip(images).toMap

      val ecuInfos = findDevice(device)

      ecuInfos.length shouldBe ecus.size
      ecuInfos.map(_.id).toSet shouldBe ecus.keys.toSet
      ecuInfos.filter(_.primary).map(_.id) shouldBe Seq(primEcu)
      ecuInfos.foreach {ecuInfo =>
        ecuInfo.image.filepath shouldBe ecus(ecuInfo.id)
      }
    }
  }

  test("device/queue (device not reported)") {
    withRandomNamespace {implicit ns =>
      val (device, _, ecuSerials) = createDeviceWithImages(afn, bfn)
      val targets = setRandomTargets(device, ecuSerials)

      val q = deviceQueueOk(device)
      q.map(_.targets) shouldBe Seq(targets)
    }
  }

  test("device/queue (device reported)") {
    withRandomNamespace {implicit ns =>
      val (device, _, ecuSerials) = createDeviceWithImages(afn, bfn)

      val reportedVersions = 42
      setCampaign(device, reportedVersions).futureValue
      setDeviceVersion(device, reportedVersions).futureValue

      val targets = setRandomTargets(device, ecuSerials)

      val q = deviceQueueOk(device)
      q.map(_.targets) shouldBe Seq(targets)
    }
  }

  test("device/queue (device reported) with bigger queue") {
    withRandomNamespace {implicit ns =>
      val (device, _, ecuSerials) = createDeviceWithImages(afn, bfn)

      val reportedVersions = 42
      setCampaign(device, reportedVersions).futureValue
      setDeviceVersion(device, reportedVersions).futureValue

      val targets = setRandomTargets(device, ecuSerials)
      val targets2 = setRandomTargets(device, ecuSerials)

      val q = deviceQueueOk(device)
      q.map(_.targets) shouldBe Seq(targets, targets2)
    }
  }
}
