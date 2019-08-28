package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminDataType._
import com.advancedtelematic.director.data.AssignmentDataType.CancelAssignments
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.TargetItemCustom
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Generators._
import com.advancedtelematic.director.data.UptaneDataType.FileInfo
import com.advancedtelematic.director.db.{DbSignedRoleRepositorySupport, RepoNamespaceRepositorySupport}
import com.advancedtelematic.director.util._
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.data.ErrorRepresentation
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateEvent, _}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.TargetsRole
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, SignedPayload}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalactic.source.Position


trait AssignmentResources {
  self: DirectorSpec with RouteResourceSpec with NamespacedTests with AdminResources =>

  def createAssignment(deviceId: DeviceId, hwId: HardwareIdentifier, targetUpdateO: Option[TargetUpdateRequest] = None,
                       correlationIdO: Option[CorrelationId] = None)(checkV: => Any)(implicit ns: Namespace, pos: Position): AssignUpdateRequest = {
    val correlationId = correlationIdO.getOrElse(GenCorrelationId.generate)

    val targetUpdate = targetUpdateO.getOrElse(GenTargetUpdateRequest.generate)
    val mtu = MultiTargetUpdate(Map(hwId -> targetUpdate))

    val mtuId = Post(apiUri("multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

    val assignment = AssignUpdateRequest(correlationId, Seq(deviceId), mtuId)

    Post(apiUri("assignments"), assignment).namespaced ~> routes ~> check(checkV)

    assignment
  }


  def createAssignmentOk(deviceId: DeviceId, hwId: HardwareIdentifier, targetUpdateO: Option[TargetUpdateRequest] = None,
                         correlationIdO: Option[CorrelationId] = None)(implicit ns: Namespace, pos: Position): AssignUpdateRequest = {
    createAssignment(deviceId, hwId, targetUpdateO, correlationIdO) {
      status shouldBe StatusCodes.Created
    }
  }

  def getDeviceAssignment[T](deviceId: DeviceId)(checkFn: => T)(implicit ns: Namespace, pos: Position): T = {
    Get(apiUri(s"assignments/${deviceId.show}")).namespaced ~> routes ~> check(checkFn)
  }

  def getDeviceAssignmentOk(deviceId: DeviceId)(implicit ns: Namespace, pos: Position): Seq[QueueResponse] = {
    getDeviceAssignment(deviceId) {
      status shouldBe StatusCodes.OK
      responseAs[Seq[QueueResponse]]
    }
  }

  def getTargetsOk(regDev: AdminResources.RegisterDeviceResult)(implicit ns: Namespace): SignedPayload[TargetsRole] = {
    Get(apiUri(s"device/${regDev.deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }
  }

  def cancelAssignmentsOk(deviceIds: Seq[DeviceId])(implicit ns: Namespace): Seq[DeviceId] = {
    Patch(apiUri("assignments"), CancelAssignments(deviceIds)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]]
    }
  }
}

class AssignmentsResourceSpec extends DirectorSpec
  with RouteResourceSpec
  with RepoNamespaceRepositorySupport
  with DbSignedRoleRepositorySupport with AdminResources with AssignmentResources with RepositorySpec {

  override implicit val msgPub = new MockMessageBus

  testWithRepo("Can create an assignment for existing devices") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)
  }

  testWithRepo("GET queue for affected devices includes newly created assignment") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    val assignment = createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    val queue = getDeviceAssignmentOk(assignment.devices.head)
    queue.map(_.correlationId) should contain(assignment.correlationId)
  }

  testWithRepo("returns PrimaryIsNotListedForDevice when ecus to register do not include primary ecu") { implicit ns =>
    val device = DeviceId.generate
    val (regEcu, _) = GenRegisterEcuKeys.generate
    val ecu = GenEcuIdentifier.generate
    val regDev = RegisterDevice(device.some, ecu, List(regEcu))

    Post(apiUri("admin/devices"), regDev).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest

      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PrimaryIsNotListedForDevice
    }
  }

  testWithRepo("can GET devices affected by assignment") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk()

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdate(Map(regDev0.primary.hardwareId -> targetUpdate))

    val mtuId = Post(apiUri("multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

    Get(apiUri(s"assignments/devices?mtuId=${mtuId.show}&ids=${regDev0.deviceId.show},${regDev1.deviceId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]] should contain(regDev0.deviceId)
      responseAs[Seq[DeviceId]] shouldNot contain(regDev1.deviceId)
    }
  }

  testWithRepo("Only creates assignments for affected devices") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk()

    createAssignmentOk(regDev0.deviceId, regDev0.primary.hardwareId)

    val queue0 = getDeviceAssignmentOk(regDev0.deviceId)

    queue0 shouldNot be(empty)

    val queue1 = getDeviceAssignmentOk(regDev1.deviceId)

    queue1 shouldBe empty
  }

  testWithRepo("Only creates assignments for affected ecus in a device") { implicit ns =>
    val regDev = registerAdminDeviceWithSecondariesOk()

    createAssignmentOk(regDev.deviceId, regDev.secondaries.values.head.hardwareId)

    val queue = getDeviceAssignmentOk(regDev.deviceId)

    queue.head.targets.get(regDev.primary.ecuSerial) should be(empty)
    queue.head.targets.get(regDev.secondaries.keys.head) should be(defined)
  }

  testWithRepo("fails if no ecus are affected by assignment") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    createAssignment(regDev.deviceId, GenHardwareIdentifier.generate) {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.NoDevicesAffected
    }
  }

  testWithRepo("create assignment fails if there is a created assignment for an ecu already") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    createAssignment(regDev.deviceId, regDev.primary.hardwareId) {
      status shouldBe StatusCodes.Conflict
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.AssignmentExists
    }
  }

  testWithRepo("PATCH assignments cancels assigned updates") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    cancelAssignmentsOk(Seq(regDev.deviceId)) shouldBe Seq(regDev.deviceId)

    val msg = msgPub.wasReceived[DeviceUpdateEvent] { msg: DeviceUpdateEvent =>
      msg.deviceUuid == regDev.deviceId
    }

    msg shouldBe defined
    msg.get shouldBe a [DeviceUpdateCanceled]
  }

  testWithRepo("DELETE assignments can only cancel if update is not in-flight") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    // make it inflight
    getTargetsOk(regDev)

    cancelAssignmentsOk(Seq(regDev.deviceId)) shouldBe Seq.empty
  }

  testWithRepo("published DeviceUpdateAssigned message") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    val msg = msgPub.wasReceived[DeviceUpdateEvent] { msg: DeviceUpdateEvent =>
      msg.deviceUuid == regDev.deviceId
    }

    msg shouldBe defined
  }

  testWithRepo("Device ignores canceled assignment and sees new assignment created afterwards") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    cancelAssignmentsOk(Seq(regDev.deviceId)) shouldBe Seq(regDev.deviceId)

    val t1 = getTargetsOk(regDev)
    t1.signed.targets shouldBe 'empty

    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    val t2 = getTargetsOk(regDev)
    // check if a target is addressing our ECU:
    val targetItemCustom = t2.signed.targets.values.head.customParsed[TargetItemCustom]
    targetItemCustom.get.ecuIdentifiers.keys.head shouldBe regDev.ecus.keys.head
  }

}
