package com.advancedtelematic.director.daemon

import java.time.Instant

import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Generators._
import com.advancedtelematic.director.data.Messages
import com.advancedtelematic.director.db.DeviceManifestRepositorySupport
import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libats.data.DataType
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import io.circe.syntax._
import org.scalatest.OptionValues._

import scala.concurrent.ExecutionContext

class DeviceManifestResportedListenerSpec extends DirectorSpec
  with DatabaseSpec
  with DeviceManifestRepositorySupport {

  val defaultNs = DataType.Namespace(this.getClass.getName)

  implicit lazy val ec = ExecutionContext.global

  lazy val listener = new DeviceManifestReportedListener()

  test("it saves manifest to database") {
    val manifest = GenDeviceManifest.generate
    val signedManifest = SignedPayload(Seq.empty, manifest.asJson, manifest.asJson)

    val msg = Messages.DeviceManifestReported(defaultNs, DeviceId.generate(), signedManifest, Instant.now())

    listener.apply(msg).futureValue

    val (saved, receivedAt) = deviceManifestRepository.find(msg.deviceId).futureValue.value

    saved shouldBe msg.manifest.signed
    receivedAt shouldBe msg.receivedAt
  }

  test("it doesn't new row save if manifest did not change") {
    val manifest = GenDeviceManifest.generate
    val signedManifest = SignedPayload(Seq.empty, manifest.asJson, manifest.asJson)

    val msg = Messages.DeviceManifestReported(defaultNs, DeviceId.generate(), signedManifest, Instant.now())

    listener.apply(msg).futureValue
    listener.apply(msg.copy(receivedAt = Instant.now().plusSeconds(30))).futureValue

    val (saved, receivedAt) = deviceManifestRepository.find(msg.deviceId).futureValue.value

    saved shouldBe msg.manifest.signed
    receivedAt shouldBe msg.receivedAt
  }

  test("it saves new manifest if manifest changed") {

  }
}
