package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.DataType.{FileCacheRequest, TargetsCustom}
import com.advancedtelematic.director.data.Codecs.targetsCustomEncoder
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheDB, SetTargets}
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.util.{DirectorSpec, NamespaceTag}
import com.advancedtelematic.libats.data.DataType.{CampaignId, MultiTargetUpdateId}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{RootRole, SnapshotRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder}
import io.circe.syntax._
import java.time.Instant

import org.scalatest.OptionValues._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.{DeviceRequest, EdGenerators, FileCacheRequestStatus, KeyGenerators, RsaGenerators}
import com.advancedtelematic.director.util.NamespaceTag.NamespaceTag
import com.advancedtelematic.libats.data.EcuIdentifier
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.{Matcher, MatchResult}
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}

import scala.concurrent.Future

object FileCacheSpec {
  private[FileCacheSpec] final case class Device(deviceId: DeviceId, primaryEcu: EcuIdentifier)
}

trait FileCacheSpec extends DirectorSpec
    with KeyGenerators
    with DatabaseSpec
    with BeforeAndAfterAll
    with Eventually
    with FileCacheDB
    with DeviceRepositorySupport
    with Requests {

  private val timeout = Timeout(Span(50, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  def isAvailable[T : Decoder : Encoder](device: DeviceId, file: String): SignedPayload[T] =
    Get(apiUri(s"device/${device.show}/$file")) ~> routes ~> check {
      val resp = responseAs[SignedPayload[T]]
      status shouldBe StatusCodes.OK
      resp
    }

  def beAfter(other: Instant): Matcher[Instant] = new Matcher[Instant] {
    def apply(me: Instant) = MatchResult( me.isAfter(other),
                                          me + " was not after " + other,
                                          me + " was after " + other)
  }

  val directorRepo = new DirectorRepo(keyserverClient)
  val deviceRepo = deviceRepository
  override def beforeAll() {
    super.beforeAll()
    directorRepo.findOrCreate(defaultNs, testKeyType).futureValue
  }

  test("Files are generated") {
    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targetImage = GenCustomImage.generate.copy(diffFormat = None)

    val target = SetTarget(Map(primEcu -> targetImage))

    SetTargets.setTargets(defaultNs, Seq(device -> target)).futureValue

    eventually(timeout, interval) {
      val ts = isAvailable[TimestampRole](device, "timestamp.json")
      ts.signed.version shouldBe 1
      isAvailable[SnapshotRole](device, "snapshot.json")
      isAvailable[TargetsRole](device, "targets.json")
      isAvailable[RootRole](device, "root.json")
    }
  }

  test("Files are generated with correlationId") {
    val device = DeviceId.generate
    val updateId = UpdateId.generate
    val correlationId = MultiTargetUpdateId(updateId.uuid)

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targetImage = GenCustomImage.generate.copy(diffFormat = None)

    val target = SetTarget(Map(primEcu -> targetImage))

    SetTargets.setTargets(defaultNs, Seq(device -> target), Some(correlationId)).futureValue

    eventually(timeout, interval) {
      val ts = isAvailable[TimestampRole](device, "timestamp.json")
      ts.signed.version shouldBe 1
      isAvailable[SnapshotRole](device, "snapshot.json")
      val tg = isAvailable[TargetsRole](device, "targets.json")
      tg.signed.custom shouldBe Some(TargetsCustom(Some(correlationId)).asJson)
      isAvailable[RootRole](device, "root.json")
    }
  }

  test("Versions increases on error") {
    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targetImage = GenCustomImage.generate.copy(diffFormat = None)
    val target = SetTarget(Map(primEcu -> targetImage))

    val correlationId = MultiTargetUpdateId(java.util.UUID.randomUUID())
    SetTargets.setTargets(defaultNs, Seq(device -> target), Some(correlationId)).futureValue

    val ecuManifest2 = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest2 = GenSignedDeviceManifest(primEcu, ecuManifest2).generate

    // this will fail and reset the target to be empty
    updateManifestOk(device, devManifest2)

    eventually(timeout, interval) {
      val ts = isAvailable[TimestampRole](device, "timestamp.json")
      ts.signed.version shouldBe 2

      val snap = isAvailable[SnapshotRole](device, "snapshot.json")
      snap.signed.version shouldBe 2

      val targ = isAvailable[TargetsRole](device, "targets.json")
      targ.signed.version shouldBe 2
      targ.signed.targets shouldBe Map.empty

      isAvailable[RootRole](device, "root.json")
    }
  }

  test("Can schedule several updates for the same device at the same time") {
    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val targets = for (_ <- 0 until 10) yield {
      val targetImage = GenCustomImage.generate
      SetTarget(Map(primEcu -> targetImage))
    }

    Future.traverse(targets){ target =>
      SetTargets.setTargets(defaultNs, Seq(device -> target))
    }.futureValue
  }


  import FileCacheSpec.Device
  private[this] def registerDevice(): Device = {
    val deviceId = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(deviceId, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)
    Device(deviceId, primEcu)
  }

  private[this] def updateManifest(device: Device): Unit = {
    val ecuManifest = Seq(GenSignedEcuManifest(device.primaryEcu).generate)
    val devManifest = GenSignedDeviceManifest(device.primaryEcu, ecuManifest).generate

    updateManifestOk(device.deviceId, devManifest)
  }

  test("expired requests are re-generating") {
    val device @ Device(deviceId, primEcu) = registerDevice()
    updateManifest(device)

    val targetImage = GenCustomImage.generate.copy(diffFormat = None)
    val target = SetTarget(Map(primEcu -> targetImage))

    val correlationId = CampaignId(java.util.UUID.randomUUID())
    SetTargets.setTargets(defaultNs, Seq(deviceId -> target), Some(correlationId)).futureValue

    val oldTime = isAvailable[TimestampRole](deviceId, "timestamp.json").signed.expires
    isAvailable[SnapshotRole](deviceId, "snapshot.json").signed.expires shouldBe oldTime
    val oldTargets = isAvailable[TargetsRole](deviceId, "targets.json")
    oldTargets.signed.expires shouldBe oldTime
    oldTargets.signed.custom.value.as[TargetsCustom].toOption.flatMap(_.correlationId).value should be(correlationId)
    isAvailable[RootRole](deviceId, "root.json")

    makeFilesExpire(deviceId).futureValue

    Future { Thread.sleep(1100) }.futureValue

    val newTime = isAvailable[TimestampRole](deviceId, "timestamp.json").signed.expires
    newTime should beAfter(oldTime)
    isAvailable[SnapshotRole](deviceId, "snapshot.json").signed.expires shouldBe newTime

    val newTargets = isAvailable[TargetsRole](deviceId, "targets.json")
    newTargets.signed.expires shouldBe newTime
    newTargets.signed.version shouldBe oldTargets.signed.version + 1
    newTargets.signed.targets shouldNot be(empty)
    newTargets.signed.targets shouldBe oldTargets.signed.targets
    newTargets.signed.custom.value.as[TargetsCustom].toOption.flatMap(_.correlationId).value should be(correlationId)
  }

  test("handle difference between current version and update assignment > 1") {
    val device @ Device(deviceId, primEcu) = registerDevice()
    updateManifest(device)
    isAvailable[TargetsRole](deviceId, "targets.json").signed.version should equal(0)
    makeFilesExpire(deviceId).futureValue
    isAvailable[TargetsRole](deviceId, "targets.json").signed.version should equal(1)

    val targetImage = GenCustomImage.generate.copy(diffFormat = None)
    val target = SetTarget(Map(primEcu -> targetImage))

    val correlationId = CampaignId(java.util.UUID.randomUUID())
    SetTargets.setTargets(defaultNs, Seq(deviceId -> target), Some(correlationId)).futureValue
    generateAllPendingFiles(Some(deviceId))(new NamespaceTag { val value = "default" }).futureValue.head.device should equal(deviceId)
    deviceRepo.getCurrentVersion(deviceId).futureValue should equal(0)
    isAvailable[TargetsRole](deviceId, "targets.json").signed.version should equal(2)

    val ecuManifest: SignedPayload[DeviceRequest.EcuManifest] = GenSignedEcuManifestWithImage(primEcu, targetImage.image).generate
    val devManifest = GenSignedDeviceManifest(primEcu, Map(primEcu -> ecuManifest)).generate
    updateManifestOk(device.deviceId, devManifest)
    deviceRepo.getCurrentVersion(deviceId).futureValue should equal(2)
  }

  // https://saeljira.it.here.com/browse/OTA-4539
  test("targets.json is regenerated if it's missing a correlationId") {
    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targetImage = GenCustomImage.generate.copy(diffFormat = None)
    val target = SetTarget(Map(primEcu -> targetImage))

    val correlationId = CampaignId(java.util.UUID.randomUUID())
    SetTargets.setTargets(defaultNs, Seq(device -> target), Some(correlationId)).futureValue

    val oldTime = isAvailable[TimestampRole](device, "timestamp.json").signed.expires
    isAvailable[SnapshotRole](device, "snapshot.json").signed.expires shouldBe oldTime
    val oldTargets = isAvailable[TargetsRole](device, "targets.json")
    oldTargets.signed.expires shouldBe oldTime
    oldTargets.signed.custom.value.as[TargetsCustom].toOption.flatMap(_.correlationId).value should be(correlationId)
    isAvailable[RootRole](device, "root.json")

    val newVersion = oldTargets.signed.version + 1
    val fcr = FileCacheRequest(defaultNs, newVersion, device, FileCacheRequestStatus.PENDING, newVersion, correlationId = None)
    rolesGeneration.processFileCacheRequest(fcr, assignmentsVersion = Some(oldTargets.signed.version)).futureValue

    val newTargets = isAvailable[TargetsRole](device, "targets.json")
    newTargets.signed.version shouldBe oldTargets.signed.version + 2
    newTargets.signed.targets shouldBe oldTargets.signed.targets
    newTargets.signed.custom.value.as[TargetsCustom].toOption.flatMap(_.correlationId).value should be(correlationId)
  }
}

class RsaFileCacheSpec extends FileCacheSpec with RsaGenerators

class EdFileCacheSpec extends FileCacheSpec with EdGenerators
