package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.daemon.TufTargetWorker
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{FileCacheDB, SetMultiTargets}
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.data.ClientDataType.TargetCustom
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetName, ValidTargetFilename}
import com.advancedtelematic.libtuf_server.data.Messages.TufTargetAdded
import eu.timepit.refined.api.Refined

class AutoUpdateSpec extends DirectorSpec with FileCacheDB with NamespacedRequests with ResourceSpec {

  def regDevice(primEcuHw: HardwareIdentifier, otherEcuHw: HardwareIdentifier*)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val device = DeviceId.generate
    val regEcus = (primEcuHw :: otherEcuHw.toList).map { hw =>
      GenRegisterEcu.generate.copy(hardware_identifier = Some(hw))
    }

    val primEcu = regEcus.head.ecu_serial

    registerDeviceOk(RegisterDevice(device, primEcu, regEcus))

    val ecuManifests = regEcus.map {regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate}
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifest(device, devManifest).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    (device, primEcu, regEcus.tail.map(_.ecu_serial))
  }

  def generateTufTargetAdded(hws: HardwareIdentifier*)(implicit ns: NamespaceTag): (TufTargetAdded, TargetName) = {
    val target = GenTargetUpdate.generate
    val name = GenTargetName.generate
    val version = GenTargetVersion.generate
    val targetFormat = Some(GenTargetFormat.generate)

    val custom = TargetCustom(name, version, hws, targetFormat)
    val filename = target.target.value.refineTry[ValidTargetFilename].get
    (TufTargetAdded(ns.get, filename, target.checksum, target.targetLength, Some(custom)), name)
  }

  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)

  val hw0: HardwareIdentifier = Refined.unsafeApply("hw0")
  val hw1: HardwareIdentifier = Refined.unsafeApply("hw1")
  val hw2: HardwareIdentifier = Refined.unsafeApply("hw2")

  testWithNamespace("fetching non-existent autoupdate yield empty") { implicit ns =>
    val device = DeviceId.generate
    val ecuSerial= GenEcuSerial.generate

    findAutoUpdate(device, ecuSerial) shouldBe Seq()
  }

  testWithNamespace("setting/unsetting targetName shows up") { implicit ns =>
    val (device, primEcu, ecus) = regDevice(hw0)

    val targetName = GenTargetName.generate

    setAutoUpdate(device, primEcu, targetName)

    findAutoUpdate(device, primEcu) shouldBe Seq(targetName)

    deleteAutoUpdate(device, primEcu, targetName)

    findAutoUpdate(device, primEcu) shouldBe Seq()
  }

  testWithNamespace("multiple ecus can have auto updates") { implicit ns =>
    val (device, primEcu, Seq(ecu1, ecu2)) = regDevice(hw0, hw1, hw2)

    val targetName = GenTargetName.generate

    setAutoUpdate(device, ecu1, targetName)
    setAutoUpdate(device, ecu2, targetName)

    findAutoUpdate(device, ecu1) shouldBe Seq(targetName)
    findAutoUpdate(device, ecu2) shouldBe Seq(targetName)

    deleteAutoUpdate(device, ecu1, targetName)
    deleteAutoUpdate(device, ecu2, targetName)

    findAutoUpdate(device, ecu1) shouldBe Seq()
    findAutoUpdate(device, ecu2) shouldBe Seq()
  }

  testWithNamespace("ecu can have multiple auto updates") { implicit ns =>
    val (device, primEcu, ecus) = regDevice(hw0)

    val targetName1 = GenTargetName.generate
    val targetName2 = GenTargetName.generate

    setAutoUpdate(device, primEcu, targetName1)
    setAutoUpdate(device, primEcu, targetName2)

    findAutoUpdate(device, primEcu).toSet shouldBe Set(targetName1, targetName2)

    deleteAutoUpdate(device, primEcu, targetName1)
    findAutoUpdate(device, primEcu) shouldBe Seq(targetName2)

    deleteAutoUpdate(device, primEcu, targetName2)
    findAutoUpdate(device, primEcu) shouldBe Seq()

    setAutoUpdate(device, primEcu, targetName1)
    setAutoUpdate(device, primEcu, targetName2)
    findAutoUpdate(device, primEcu).toSet shouldBe Set(targetName1, targetName2)

    deleteAllAutoUpdate(device, primEcu)
    findAutoUpdate(device, primEcu) shouldBe Seq()
  }

  testWithNamespace("when TufTargetEvent fires, updates gets scheduled") { implicit ns =>
    val (device1, primEcu1, ecus1) = regDevice(hw0)
    val (device2, primEcu2, ecus2) = regDevice(hw0)
    val (deviceN, primEcuN, ecusN) = regDevice(hw0)

    val (tufTargetAdded, targetName) = generateTufTargetAdded(hw0)

    setAutoUpdate(device1, primEcu1, targetName)
    setAutoUpdate(device2, primEcu2, targetName)

    tufTargetWorker.action(tufTargetAdded).futureValue
    pretendToGenerate().futureValue

    def checkQueue(device: DeviceId, primEcu: EcuSerial): Unit = {
      val queue = deviceQueueOk(device)

      queue.length shouldBe 1

      val item = queue.head

      item.targets.size shouldBe 1

      item.targets(primEcu).image.filepath shouldBe tufTargetAdded.filename
      item.targets(primEcu).image.fileinfo.length shouldBe tufTargetAdded.length
    }

    checkQueue(device1, primEcu1)
    checkQueue(device2, primEcu2)

    deviceQueueOk(deviceN) shouldBe Seq()
  }

  testWithNamespace("TufTargetEvent schedule for device with multiple matching ecus") { implicit ns =>
    val (device, primEcu, Seq(ecu0, ecu1)) = regDevice(hw0, hw1, hw1)

    val (tufTargetAdded, targetName) = generateTufTargetAdded(hw1)

    setAutoUpdate(device, ecu0, targetName)
    setAutoUpdate(device, ecu1, targetName)

    tufTargetWorker.action(tufTargetAdded).futureValue
    pretendToGenerate().futureValue

    val queue = deviceQueueOk(device)

    queue.length shouldBe 1
  }
}
