package com.advancedtelematic.director.daemon

import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{TestActorRef, TestKitBase}
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.SetTargets
import com.advancedtelematic.director.http.Requests
import com.advancedtelematic.director.util.{DirectorSpec, FakeRoleStore}
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.test.DatabaseSpec
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}

class FileCacheSpec extends DirectorSpec
    with BeforeAndAfterAll
    with DatabaseSpec
    with Eventually
    with Requests
    with TestKitBase {

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  def isAvailable(device: DeviceId, file: String): Unit =
    Get(apiUri(s"device/${device.show}/$file")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  test("Files are generated") {
    val testActorRef = TestActorRef(FileCacheDaemon.props(FakeRoleStore))
    val createRepo = TestActorRef(CreateRepoActor.props(FakeRoleStore))

    createRepo ! UserCreated(defaultNs.get)

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
      isAvailable(device, "timestamp.json")
      isAvailable(device, "snapshots.json")
      isAvailable(device, "targets.json")
      isAvailable(device, "root.json")
    }
  }
}
