package com.advancedtelematic.director.daemon

import java.time.Instant
import java.time.temporal.ChronoUnit

import cats.syntax.option._
import com.advancedtelematic.director.ForceDeviceUpdateMessage
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Generators._
import com.advancedtelematic.director.db.{AssignmentsRepositorySupport, DeviceManifestRepositorySupport, DeviceRepositorySupport}
import com.advancedtelematic.director.http.{AdminResources, AssignmentResources, DeviceResources}
import com.advancedtelematic.director.util.{DeviceManifestSpec, DirectorSpec, RepositorySpec, RouteResourceSpec}
import com.advancedtelematic.libats.data.DataType.{Namespace, ResultCode, ResultDescription}
import com.advancedtelematic.libats.messaging.test.MockMessageBus
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateCompleted, DeviceUpdateEvent}
import com.advancedtelematic.libats.test.DatabaseSpec
import org.scalatest.OptionValues._

class ForceDeviceUpdateMessageSpec extends DirectorSpec with RouteResourceSpec with RepositorySpec with DeviceManifestSpec
  with DatabaseSpec
  with DeviceManifestRepositorySupport with AssignmentsRepositorySupport with DeviceRepositorySupport with AssignmentResources with DeviceResources with AdminResources {

  override implicit val msgPub = new MockMessageBus

  val subject = new ForceDeviceUpdateMessage()

  private def processDeviceAssignment(success: Boolean)(implicit ns: Namespace): DeviceId = {
    val targetUpdate = GenTargetUpdateRequest.generate

    val regDev = registerAdminDeviceOk()

    val aReq = createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId, targetUpdate.some)

    val deviceReport = GenInstallReport(regDev.primary.ecuSerial, success = success, correlationId = aReq.correlationId.some).generate
    val deviceManifestAfterTrying = buildPrimaryManifest(regDev.primary, regDev.primaryKey, targetUpdate.to, deviceReport.some)

    putManifestOk(regDev.deviceId, deviceManifestAfterTrying)

    msgPub.reset()

    regDev.deviceId
  }

  testWithRepo("sends a successful message ") { implicit ns =>
    val deviceId = processDeviceAssignment(success = true)

    subject.run(ns, since = Instant.now.minus(1, ChronoUnit.HOURS), Set.empty, dryRun = false).futureValue

    val msg = msgPub.findReceived[DeviceUpdateEvent] { d: DeviceUpdateEvent =>
      d.deviceUuid == deviceId && d.isInstanceOf[DeviceUpdateCompleted]
    }.value.asInstanceOf[DeviceUpdateCompleted]

    msg.result.success shouldBe true
    msg.result.code shouldBe ResultCode("0")
    msg.result.description shouldBe ResultDescription("All targeted ECUs were successfully updated")
  }

  testWithRepo("sends an error message") { implicit ns =>
    val deviceId = processDeviceAssignment(success = false)

    subject.run(ns, since = Instant.now.minus(1, ChronoUnit.HOURS), Set.empty, dryRun = false).futureValue

    val msg = msgPub.findReceived[DeviceUpdateEvent] { d: DeviceUpdateEvent =>
      d.deviceUuid == deviceId && d.isInstanceOf[DeviceUpdateCompleted]
    }.value.asInstanceOf[DeviceUpdateCompleted]

    msg.result.success shouldBe false
    msg.result.code shouldBe ResultCode("19")
    msg.result.description shouldBe ResultDescription("One or more targeted ECUs failed to update")
  }

  testWithRepo("limits messages to namespace") { implicit ns =>
    val deviceId = processDeviceAssignment(success = true)

    subject.run(Namespace("other namespace"), since = Instant.now.minus(1, ChronoUnit.HOURS), Set.empty, dryRun = false).futureValue

    val msg = msgPub.findReceived[DeviceUpdateEvent] { d: DeviceUpdateEvent => d.deviceUuid == deviceId }

    msg shouldBe empty
  }

  testWithRepo("limits messages by time") { implicit ns =>
    val deviceId = processDeviceAssignment(success = true)

    subject.run(ns, since = Instant.now.plusSeconds(1), Set.empty, dryRun = false).futureValue

    val msg = msgPub.findReceived[DeviceUpdateEvent] { d: DeviceUpdateEvent => d.deviceUuid == deviceId }

    msg shouldBe empty
  }

  testWithRepo("noop if using dryRun") { implicit ns =>
    val deviceId = processDeviceAssignment(success = true)

    subject.run(ns, since = Instant.now.minusSeconds(60), Set.empty, dryRun = true).futureValue

    val msg = msgPub.findReceived[DeviceUpdateEvent] { d: DeviceUpdateEvent => d.deviceUuid == deviceId }

    msg shouldBe empty
  }

  testWithRepo("limits messages to provided devices") { implicit ns =>
    val deviceId = processDeviceAssignment(success = true)

    subject.run(ns, since = Instant.now.minusSeconds(60), Set(DeviceId.generate()), dryRun = false).futureValue

    val msg = msgPub.findReceived[DeviceUpdateEvent] { d: DeviceUpdateEvent => d.deviceUuid == deviceId }

    msg shouldBe empty
  }

}
