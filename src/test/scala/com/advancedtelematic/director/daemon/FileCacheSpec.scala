package com.advancedtelematic.director.daemon

import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{TestActorRef, TestKitBase}
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{FileCacheDB, SetTargets}
import com.advancedtelematic.director.http.Requests
import com.advancedtelematic.director.util.{DirectorSpec, FakeRoleStore}
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{RootRole, SnapshotRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder}
import java.time.Instant
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.{Matcher, MatchResult}
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}
import scala.concurrent.Future

class FileCacheSpec extends DirectorSpec
    with BeforeAndAfterAll
    with DatabaseSpec
    with Eventually
    with FileCacheDB
    with Requests
    with TestKitBase {

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  def isAvailable[T : Decoder : Encoder](device: DeviceId, file: String): SignedPayload[T] =
    Get(apiUri(s"device/${device.show}/$file")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[T]]
    }

  def beAfter(other: Instant): Matcher[Instant] = new Matcher[Instant] {
    def apply(me: Instant) = MatchResult( me.isAfter(other),
                                          me + " was not after " + other,
                                          me + " was after " + other)
  }

  test("Files are generated") {
    val testActorRef = TestActorRef(FileCacheDaemon.props(FakeRoleStore))
    val directorRepo = new DirectorRepo(FakeRoleStore)
    directorRepo.findOrCreate(defaultNs)

    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targetImage = GenCustomImage.generate
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

  test("Can schedule several updates for the same device at the same time") {
    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targets = for (_ <- 0 until 10) yield {
      val targetImage = GenCustomImage.generate
      SetTarget(Map(primEcu -> targetImage))
    }

    Future.traverse(targets){ target =>
      SetTargets.setTargets(defaultNs, Seq(device -> target))
    }.futureValue
  }

  ignore("expired requests are re-generating") {
    val device = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(device, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)

    val ecuManifest = Seq(GenSignedEcuManifest(primEcu).generate)
    val devManifest = GenSignedDeviceManifest(primEcu, ecuManifest).generate

    updateManifestOk(device, devManifest)

    val targetImage = GenCustomImage.generate
    val target = SetTarget(Map(primEcu -> targetImage))

    SetTargets.setTargets(defaultNs, Seq(device -> target)).futureValue

    var oldTime:Instant = null
    eventually(timeout, interval) {
      oldTime = isAvailable[TimestampRole](device, "timestamp.json").signed.expires
      isAvailable[SnapshotRole](device, "snapshot.json").signed.expires shouldBe oldTime
      isAvailable[TargetsRole](device, "targets.json").signed.expires shouldBe oldTime
      isAvailable[RootRole](device, "root.json")
    }

    makeFilesExpire(device).futureValue

    val newTime = isAvailable[TimestampRole](device, "timestamp.json").signed.expires
    newTime should beAfter(oldTime)
    isAvailable[SnapshotRole](device, "snapshot.json").signed.expires shouldBe newTime
    isAvailable[TargetsRole](device, "targets.json").signed.expires shouldBe newTime
  }
}
