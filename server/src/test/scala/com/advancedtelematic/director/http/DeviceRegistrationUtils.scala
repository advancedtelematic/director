package com.advancedtelematic.director.http

import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.DataType.CustomImage
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.KeyGenerators
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetFilename}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import eu.timepit.refined.api.Refined
import org.scalacheck.Gen

trait DeviceRegistrationUtils extends DirectorSpec
    with KeyGenerators
    with DefaultPatience
    with NamespacedRequests
    with RouteResourceSpec {

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
      sig.updated(signed = sig.signed.copy(installed_image = newImage))
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

  def setRandomTargets(device: DeviceId, ecuSerials: Seq[EcuSerial],
                       diffFormat: Option[TargetFormat] = Gen.option(GenTargetFormat).generate)
                      (implicit ns: NamespaceTag): Map[EcuSerial, CustomImage] = {
    val targets = ecuSerials.map{ ecu =>
      ecu -> GenCustomImage.generate.copy(diffFormat = diffFormat)
    }.toMap

    setTargetsOk(device, SetTarget(targets))
    targets
  }

  def setRandomTargetsToSameImage(device: DeviceId, ecuSerials: Seq[EcuSerial],
                                  diffFormat: Option[TargetFormat] = Gen.option(GenTargetFormat).generate)
                                 (implicit ns: NamespaceTag): Map[EcuSerial, CustomImage] = {
    val image = GenCustomImage.generate.copy(diffFormat = diffFormat)
    val targets = ecuSerials.map { ecu =>
      ecu -> image
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
}
