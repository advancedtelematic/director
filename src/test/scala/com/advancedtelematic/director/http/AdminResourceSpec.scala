package com.advancedtelematic.director.http

import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs.encoderEcuManifest
import com.advancedtelematic.director.data.DataType.CustomImage
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{FileCacheDB, SetVersion}
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, TargetFilename}
import com.advancedtelematic.libtuf.data.TufDataType.HardwareIdentifier
import eu.timepit.refined.api.Refined

class AdminResourceSpec extends DirectorSpec with FileCacheDB with ResourceSpec with NamespacedRequests with SetVersion {
  def registerDeviceOk(ecus: Int)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val device = DeviceId.generate

    val ecuSerials = GenEcuSerial.listBetween(ecus, ecus).generate
    val primEcu = ecuSerials.head

    val regEcus = ecuSerials.map{ ecu => GenRegisterEcu.generate.copy(ecu_serial = ecu)}
    val regDev = RegisterDevice(device, primEcu, regEcus)

    registerDeviceOk(regDev)

    (device, primEcu, ecuSerials)
  }

  def updateTheManifest(device: DeviceId, primEcu: EcuSerial, ecus: Map[EcuSerial, TargetFilename])
                       (implicit ns: NamespaceTag): Unit = {
    val ecuManifests = ecus.keys.toSeq.map { ecu =>
      val sig = GenSignedEcuManifest(ecu).generate
      val newImage = sig.signed.installed_image.copy(filepath = ecus(ecu))
      sig.copy(signed = sig.signed.copy(installed_image = newImage))
    }

    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, devManifest)
  }

  def createDeviceWithImages(images: TargetFilename*)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val (device, primEcu, ecuSerials) = registerDeviceOk(images.length)
    val ecus = ecuSerials.zip(images).toMap

    updateTheManifest(device, primEcu, ecus)

    (device, primEcu, ecuSerials)
  }

  def registerNSDeviceOk(images: TargetFilename*)(implicit ns: NamespaceTag): DeviceId = createDeviceWithImages(images : _*)._1

  def registerHWDeviceOk(hws: HardwareIdentifier*)(implicit ns: NamespaceTag): DeviceId = {
    val device = DeviceId.generate

    val regEcus = hws.map { hw =>
      GenRegisterEcu.generate.copy(hardware_identifier = Some(hw))
    }

    val primEcu = regEcus.head.ecu_serial
    val regDev = RegisterDevice(device, primEcu, regEcus)

    registerDeviceOk(regDev)

    device
  }

  def setRandomTargets(device: DeviceId, ecuSerials: Seq[EcuSerial])(implicit ns: NamespaceTag): Map[EcuSerial, CustomImage] = {
    val targets = ecuSerials.map{ ecu =>
      ecu -> GenCustomImage.generate
    }.toMap

    setTargetsOk(device, SetTarget(targets))

    targets
  }

  val afn: TargetFilename = Refined.unsafeApply("a")
  val bfn: TargetFilename = Refined.unsafeApply("b")
  val cfn: TargetFilename = Refined.unsafeApply("c")
  val dfn: TargetFilename = Refined.unsafeApply("d")
  val ahw: HardwareIdentifier = Refined.unsafeApply("a")
  val bhw: HardwareIdentifier = Refined.unsafeApply("b")

  testWithNamespace("images/affected Can get devices with an installed image filename") { implicit ns =>
    val device1 = registerNSDeviceOk(afn, bfn)
    val device2 = registerNSDeviceOk(afn, cfn)
    val device3 = registerNSDeviceOk(dfn)

    val pag = getAffectedByImage("a")()
    pag.total shouldBe 2
    pag.values.toSet shouldBe Set(device1, device2)
  }

  testWithNamespace("images/affected Don't count devices multiple times") { implicit ns =>
    val device = registerNSDeviceOk(afn, afn, cfn)

    val pag = getAffectedByImage("a")()
    pag.total shouldBe 1
    pag.values shouldBe Seq(device)
  }

  testWithNamespace("images/affected Pagination works") { implicit ns =>
    val device1 = registerNSDeviceOk(afn, bfn)
    val device2 = registerNSDeviceOk(afn, cfn)
    val device3 = registerNSDeviceOk(afn)

    val pag1 = getAffectedByImage("a")(limit = Some(2))
    val pag2 = getAffectedByImage("a")(offset = Some(2))

    pag1.total shouldBe 3
    pag2.total shouldBe 3

    (pag1.values ++ pag2.values).length shouldBe 3
    (pag1.values ++ pag2.values).toSet shouldBe Set(device1, device2, device3)
  }

  testWithNamespace("images/affected Ignores devices in a campaign") { implicit ns =>
    val device1 = registerNSDeviceOk(afn, bfn)
    val device2 = registerNSDeviceOk(afn)

    setCampaign(device1, 1).futureValue
    pretendToGenerate.futureValue

    val pag = getAffectedByImage("a")()
    pag.total shouldBe 1
    pag.values shouldBe Seq(device2)
  }

  testWithNamespace("images/affected Includes devices that are at the latest target") { implicit ns =>
    val device1 = registerNSDeviceOk(afn, bfn)
    val device2 = registerNSDeviceOk(afn)

    setCampaign(device1, 1).futureValue
    setDeviceVersion(device1, 1).futureValue

    val pag = getAffectedByImage("a")()
    pag.total shouldBe 2
    pag.values.toSet shouldBe Set(device1, device2)
  }

  testWithNamespace("images/installed_count returns the count of ECUs a given image is installed on") { implicit ns =>
    val device1 = registerNSDeviceOk(afn, bfn)
    val device2 = registerNSDeviceOk(afn)
    val device3 = registerNSDeviceOk(cfn, cfn)

    getCountInstalledImages(Seq(afn)) shouldBe Map(afn -> 2)
    getCountInstalledImages(Seq(afn, bfn)) shouldBe Map(afn -> 2, bfn -> 1)
    getCountInstalledImages(Seq(cfn)) shouldBe Map(cfn -> 2)
    getCountInstalledImages(Seq(dfn)) shouldBe Map()
    getCountInstalledImages(Seq(afn, dfn)) shouldBe Map(afn -> 2)
  }

  testWithNamespace("devices/hardware_identifiers returns all hardware_ids") { implicit ns =>
    val device1 = registerHWDeviceOk(ahw, bhw)
    val device2 = registerHWDeviceOk(bhw)

    val pag = getHw()
    pag.total shouldBe 2
    pag.values.toSet shouldBe Set(ahw,bhw)
  }

  testWithNamespace("devices/id/ecus/public_key can get public key") { implicit ns =>
    val device = DeviceId.generate

    val ecuSerials = GenEcuSerial.listBetween(2, 5).generate
    val primEcu = ecuSerials.head

    val regEcus = ecuSerials.map{ ecu => GenRegisterEcu.generate.copy(ecu_serial = ecu)}
    val regDev = RegisterDevice(device, primEcu, regEcus)

    registerDeviceOk(regDev)

    regEcus.foreach { regEcu =>
      findPublicKeyOk(device, regEcu.ecu_serial) shouldBe regEcu.clientKey
    }
  }

  testWithNamespace("devices/id gives a list of ecuresponses") { implicit ns =>
    val images = Seq(afn, bfn)
    val (device, primEcu, ecusSerials) = createDeviceWithImages(images : _*)
    val ecus = ecusSerials.zip(images).toMap

    val ecuInfos = findDeviceOk(device)

    ecuInfos.length shouldBe ecus.size
    ecuInfos.map(_.id).toSet shouldBe ecus.keys.toSet
    ecuInfos.filter(_.primary).map(_.id) shouldBe Seq(primEcu)
    ecuInfos.foreach {ecuInfo =>
      ecuInfo.image.filepath shouldBe ecus(ecuInfo.id)
    }
  }

  testWithNamespace("device/queue (device not reported)") { implicit ns =>
    val (device, _, ecuSerials) = createDeviceWithImages(afn, bfn)
    val targets = setRandomTargets(device, ecuSerials)

    val q = deviceQueueOk(device)
    q.map(_.targets) shouldBe Seq(targets)
  }

  testWithNamespace("device/queue (device reported)") { implicit ns =>
    val (device, _, ecuSerials) = createDeviceWithImages(afn, bfn)

    val reportedVersions = 42
    setCampaign(device, reportedVersions).futureValue
    setDeviceVersion(device, reportedVersions).futureValue

    val targets = setRandomTargets(device, ecuSerials)

    val q = deviceQueueOk(device)
    q.map(_.targets) shouldBe Seq(targets)
  }

  testWithNamespace("device/queue (device reported) with bigger queue") { implicit ns =>
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
