package com.advancedtelematic.director.http

import com.advancedtelematic.diff_service.data.DataType.CreateDiffInfoRequest
import com.advancedtelematic.director.data.AdminRequest.RegisterDevice
import com.advancedtelematic.director.data.Codecs.{decoderTargetCustom, encoderEcuManifest}
import com.advancedtelematic.director.data.DataType.{MultiTargetUpdateRequest, TargetCustom, TargetUpdate, TargetUpdateRequest}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.{EdGenerators, KeyGenerators, RsaGenerators}
import com.advancedtelematic.director.db.{FileCacheRequestRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, ValidTargetFilename}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.OSTREE
import eu.timepit.refined.api.Refined

import scala.concurrent.Future

trait DiffSpec extends DirectorSpec
    with KeyGenerators
    with DefaultPatience
    with RouteResourceSpec
    with NamespacedRequests
    with FileCacheRequestRepositorySupport
    with RepoNameRepositorySupport {
  val ato: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("a"))
  val bto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("b"))
  val cto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("c"))
  val dto: TargetUpdate = GenTargetUpdate.generate.copy(target = Refined.unsafeApply("d"))
  val ahw: HardwareIdentifier = Refined.unsafeApply("a")
  val bhw: HardwareIdentifier = Refined.unsafeApply("b")
  val chw: HardwareIdentifier = Refined.unsafeApply("c")
  val dhw: HardwareIdentifier = Refined.unsafeApply("d")

  def registerNSDeviceOk(hwimages: (HardwareIdentifier, TargetUpdate)*)(implicit ns: NamespaceTag): DeviceId = {
    val device = DeviceId.generate

    val regEcus = hwimages.map { case (hw, _) =>
      GenRegisterEcu.generate.copy(hardware_identifier = Some(hw))
    }

    val primEcu = regEcus.head.ecu_serial

    registerDeviceOk(RegisterDevice(device, primEcu, regEcus))

    val ecuManifest = hwimages.zip(regEcus.map(_.ecu_serial)).map {case ((hw, target), ecu) =>
      val sig = GenSignedEcuManifest(ecu).generate
      sig.copy(signed = sig.signed.copy(installed_image = target.image))
    }

    updateManifestOk(device, GenSignedDeviceManifest(primEcu, ecuManifest).generate)

    device
  }

  def createMtu(hwimages: (HardwareIdentifier, (TargetUpdate, Boolean))*)(implicit ns: NamespaceTag): UpdateId = {
    val mtu = MultiTargetUpdateRequest(hwimages.toMap.mapValues{case (target, generateDiff) => TargetUpdateRequest(None, target, OSTREE, generateDiff)})
    createMultiTargetUpdateOK(mtu)
  }

  def generateAllPendingFiles(): Future[Unit] = for {
    pends <- fileCacheRequestRepository.findPending()
    _ <- Future.traverse(pends){fcr => rolesGeneration.processFileCacheRequest(fcr)}
  } yield ()

  def createRepo()(implicit ns: NamespaceTag): Future[Unit] = {
    val repoId = RepoId.generate
    for {
      file <- keyserverClient.createRoot(repoId)
      _ <- repoNameRepository.persist(ns.get, repoId)
      } yield ()
  }

  testWithNamespace("device waits for diff") { implicit ns =>
    createRepo().futureValue
    val device = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val update = createMtu(ahw -> ((bto, true)))
    val diffRequests = Seq(CreateDiffInfoRequest(OSTREE, ato, bto))
    diffServiceClient.createDiffInfo(ns.get, diffRequests).futureValue

    var timestamp = fetchTimestampFor(device)
    timestamp.signed.version shouldBe 0

    scheduleOne(device, update)

    timestamp = fetchTimestampFor(device)
    timestamp.signed.version shouldBe 0

    val diffInfo = GenDiffInfo.generate
    diffServiceClient.generate(OSTREE, ato, bto, diffInfo)

    timestamp = fetchTimestampFor(device)
    timestamp.signed.version shouldBe 1

    val targets = fetchTargetsFor(device)
    targets.signed.targets(bto.target.value.refineTry[ValidTargetFilename].get).custom.get.as[TargetCustom].right.get.diff.get shouldBe diffInfo
  }
}

class RsaDiffSpec extends DiffSpec with RsaGenerators

class EdDiffSpec extends DiffSpec with EdGenerators
