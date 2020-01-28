package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminDataType.{QueueResponse, RegisterDevice}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Generators._
import com.advancedtelematic.director.util._
import com.advancedtelematic.libats.data.DataType.{Namespace, ResultCode, ResultDescription}
import com.advancedtelematic.libats.data.ErrorRepresentation
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceSeen, DeviceUpdateCompleted, _}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{RootRole, SnapshotRole, TargetsRole, TimestampRole, TufRole}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder, Json}
import org.scalactic.source.Position
import org.scalatest.Inspectors


trait DeviceResources {
  self: DirectorSpec with ResourceSpec with RouteResourceSpec =>

  def registerDeviceOk()(implicit namespace: Namespace, pos: Position): DeviceId = {
    val ecus = GenRegisterEcu.generate
    val primaryEcu = ecus.ecu_serial

    val deviceId = DeviceId.generate()
    val req = RegisterDevice(deviceId.some, primaryEcu, Seq(ecus))

    Post(apiUri(s"device/${deviceId.show}/ecus"), req).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    deviceId
  }

  def getDeviceRole[T : Encoder : Decoder](deviceId: DeviceId, version: Option[Int] = None)
                                          (implicit namespace: Namespace, pos: Position, tufRole: TufRole[T]): RouteTestResult = {
    val versionStr = version.map(_ + ".").getOrElse("")
    Get(apiUri(s"device/${deviceId.show}/$versionStr${tufRole.metaPath.value}")).namespaced ~> routes
  }

  def getDeviceRoleOk[T : Encoder : Decoder](deviceId: DeviceId, version: Option[Int] = None)(implicit namespace: Namespace, pos: Position, tufRole: TufRole[T]): SignedPayload[T] = {
    getDeviceRole[T](deviceId, version) ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[T]]
    }
  }

  def putManifest[T](deviceId: DeviceId, manifest: SignedPayload[DeviceManifest])(fn: => T)(implicit ns: Namespace, pos: Position)  = {
    Put(apiUri(s"device/${deviceId.show}/manifest"), manifest).namespaced ~> routes ~> check(fn)
  }

  def putManifestOk(deviceId: DeviceId, manifest: SignedPayload[DeviceManifest])(implicit ns: Namespace, pos: Position): Unit = {
    putManifest(deviceId, manifest) {
      status shouldBe StatusCodes.OK
    }
  }

  def fetchRoleOk[T : Encoder : Decoder](deviceId: DeviceId)(implicit ns: Namespace, tufRole: TufRole[T]): SignedPayload[T] = {
    Get(apiUri(s"device/${deviceId.show}/${tufRole.metaPath}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[T]]
    }
  }

}

class DeviceResourceSpec extends DirectorSpec
  with RouteResourceSpec with AdminResources with AssignmentResources
  with DeviceManifestSpec with RepositorySpec with Inspectors with DeviceResources {

  override implicit val msgPub = new MockMessageBus

  def forceRoleExpire[T](deviceId: DeviceId)(implicit tufRole: TufRole[T]): Unit = {
    import slick.jdbc.MySQLProfile.api._
    val sql = sql"update signed_roles set expires_at = '1970-01-01 00:00:00' where device_id = '#${deviceId.uuid.toString}' and role = '#${tufRole.typeStr}'"
    db.run(sql.asUpdate).futureValue
  }

  testWithNamespace("accepts a device registering ecus") { implicit ns =>
    createRepoOk()
    registerDeviceOk()
  }

  testWithRepo("fails when primary ecu is not defined in ecus") { implicit ns =>
    val ecus = GenRegisterEcu.generate
    val primaryEcu = GenEcuIdentifier.generate
    val deviceId = DeviceId.generate()
    val req = RegisterDevice(deviceId.some, primaryEcu, Seq(ecus))

    Post(apiUri(s"device/${deviceId.show}/ecus"), req).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe Errors.PrimaryIsNotListedForDevice.code
    }

    deviceId
  }

  testWithRepo("targets.json is empty after register") { implicit ns =>
    val deviceId = registerDeviceOk()

    Get(apiUri(s"device/${deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val signedPayload = responseAs[SignedPayload[TargetsRole]].signed
      signedPayload.targets shouldBe empty
    }
  }

  testWithRepo("fetches a root.json for a device") { implicit ns =>
    val deviceId = registerDeviceOk()

    Get(apiUri(s"device/${deviceId.show}/1.root.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]].signed shouldBe a[RootRole]
    }
  }

  testWithRepo("can GET root.json without specifying version") { implicit ns =>
    val deviceId = registerDeviceOk()

    Get(apiUri(s"device/${deviceId.show}/root.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]].signed shouldBe a[RootRole]
    }
  }

  testWithRepo("can GET timestamp") { implicit ns =>
    val deviceId = registerDeviceOk()

    Get(apiUri(s"device/${deviceId.show}/timestamp.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TimestampRole]].signed shouldBe a[TimestampRole]
    }
  }

  testWithRepo("can get snapshots") { implicit ns =>
    val deviceId = registerDeviceOk()

    Get(apiUri(s"device/${deviceId.show}/snapshot.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[SnapshotRole]].signed shouldBe a[SnapshotRole]
    }
  }

  testWithRepo("GET on targets.json contains target after assignment") { implicit ns =>
    val targetUpdate = GenTargetUpdateRequest.generate
    val regDev = registerAdminDeviceOk()
    val deviceId = regDev.deviceId
    createAssignmentOk(deviceId, regDev.primary.hardwareId, targetUpdate.some)

    Get(apiUri(s"device/${deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val targets = responseAs[SignedPayload[TargetsRole]].signed
      targets.version shouldBe 2
      targets.targets.keys should contain(targetUpdate.to.target)
      targets.targets(targetUpdate.to.target).hashes.values should contain(targetUpdate.to.checksum.hash)
      targets.targets(targetUpdate.to.target).length shouldBe targetUpdate.to.targetLength
    }
  }

  testWithRepo("device can PUT a valid manifest") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val deviceManifest = buildPrimaryManifest(regDev.primary, regDev.primaryKey,targetUpdate.to)

    putManifestOk(regDev.deviceId, deviceManifest)
  }

  testWithRepo("device queue is cleared after successful PUT manifest") { implicit ns =>
    val registerDevice = registerAdminDeviceOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val deviceId = registerDevice.deviceId
    createAssignmentOk(registerDevice.deviceId, registerDevice.primary.hardwareId, targetUpdate.some)

    val deviceManifest = buildPrimaryManifest(registerDevice.primary, registerDevice.primaryKey, targetUpdate.to)

    putManifestOk(deviceId, deviceManifest)

    Get(apiUri(s"assignments/${deviceId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[List[Json]] shouldBe empty
    }
  }

  testWithRepo("fails when manifest is not properly signed by primary") { implicit ns =>
    val registerDevice = registerAdminDeviceOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val deviceId = registerDevice.deviceId
    val key = GenKeyType.generate.crypto.generateKeyPair()

    val deviceManifest = buildPrimaryManifest(registerDevice.primary, key, targetUpdate.to)

    Put(apiUri(s"device/${deviceId.show}/manifest"), deviceManifest).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.Manifest.SignatureNotValid
    }
  }

  testWithRepo("accepts manifest signed by secondary and primary") { implicit ns =>
    val regDev = registerAdminDeviceWithSecondariesOk()
    val (secondary, secondaryKey) = regDev.secondaryKeys.head
    val targetUpdate = GenTargetUpdateRequest.generate
    val deviceManifest = buildSecondaryManifest(regDev.primary.ecuSerial, regDev.primaryKey, secondary, secondaryKey, Map(regDev.primary.ecuSerial -> targetUpdate.to, secondary -> targetUpdate.to))

    putManifestOk(regDev.deviceId, deviceManifest)
  }

  testWithRepo("fails when manifest is not properly signed by secondary") { implicit ns =>
    val regDev = registerAdminDeviceWithSecondariesOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val (secondary, realKey) = regDev.secondaryKeys.head
    val secondaryKey = GenTufKeyPair.generate
    val deviceManifest = buildSecondaryManifest(regDev.primary.ecuSerial, regDev.primaryKey, secondary, secondaryKey, Map(regDev.primary.ecuSerial -> targetUpdate.to, secondary -> targetUpdate.to))

    Put(apiUri(s"device/${regDev.deviceId.show}/manifest"), deviceManifest).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.Manifest.SignatureNotValid
      responseAs[ErrorRepresentation].description should include(s"not signed with key ${realKey.pubkey.id}")
    }
  }

  testWithRepo("returns exact same targets.json if assignments did not change") { implicit ns =>
    import com.advancedtelematic.libtuf.crypt.CanonicalJson._
    val regDev = registerAdminDeviceOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId, targetUpdate.some)

    val firstTargets = Get(apiUri(s"device/${regDev.deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

    Thread.sleep(1000)

    val secondTargets = Get(apiUri(s"device/${regDev.deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

    if(firstTargets.json.canonical != secondTargets.json.canonical)
      fail(s"targets.json $firstTargets is not the same as $secondTargets")
  }

  testWithRepo("returns a refreshed version of targets if it expires") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    val firstTargets = fetchRoleOk[TargetsRole](regDev.deviceId)

    forceRoleExpire[TargetsRole](regDev.deviceId)

    val secondTargets = fetchRoleOk[TargetsRole](regDev.deviceId)

    secondTargets.signed.expires.isAfter(firstTargets.signed.expires)
    firstTargets.signed.version shouldBe 1
    secondTargets.signed.version shouldBe 2
  }

  testWithRepo("a refreshed targets returns the same assignments as before, even if they were completed") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    val targetUpdate = GenTargetUpdateRequest.generate
    createAssignmentOk(regDev.deviceId, regDev.primary.hardwareId, targetUpdate.some)

    val firstTargets = fetchRoleOk[TargetsRole](regDev.deviceId)

    val deviceManifest = buildPrimaryManifest(regDev.primary, regDev.primaryKey, targetUpdate.to)

    putManifestOk(regDev.deviceId, deviceManifest)

    forceRoleExpire[TargetsRole](regDev.deviceId)

    val secondTargets = fetchRoleOk[TargetsRole](regDev.deviceId)

    secondTargets.signed.expires.isAfter(firstTargets.signed.expires)
    secondTargets.signed.targets shouldBe firstTargets.signed.targets
  }

  testWithRepo("returns a refreshed version of snapshots if it expires") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    val first = fetchRoleOk[SnapshotRole](regDev.deviceId)

    forceRoleExpire[SnapshotRole](regDev.deviceId)

    val second = fetchRoleOk[SnapshotRole](regDev.deviceId)

    second.signed.expires.isAfter(first.signed.expires)
    first.signed.version shouldBe 1
    second.signed.version shouldBe 2
  }

  testWithRepo("returns a refreshed version of timestamps if it expires") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    val first = fetchRoleOk[TimestampRole](regDev.deviceId)

    forceRoleExpire[TimestampRole](regDev.deviceId)

    val second = fetchRoleOk[TimestampRole](regDev.deviceId)

    second.signed.expires.isAfter(first.signed.expires)
    first.signed.version shouldBe 1
    second.signed.version shouldBe 2
  }

  testWithRepo("moves queue status to inflight = true after device gets targets containing assignment") { implicit ns =>
    val registerDevice = registerAdminDeviceOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val deviceId = registerDevice.deviceId
    val correlationId = GenCorrelationId.generate
    createAssignmentOk(registerDevice.deviceId, registerDevice.primary.hardwareId, targetUpdate.some, correlationId.some)

    getDeviceRoleOk[TargetsRole](deviceId)

    getDeviceAssignment(deviceId) {
      status shouldBe StatusCodes.OK
      val firstQueueItem = responseAs[List[QueueResponse]].head

      firstQueueItem.targets(registerDevice.primary.ecuSerial).image.filepath shouldBe targetUpdate.to.target
      firstQueueItem.inFlight shouldBe true
      firstQueueItem.correlationId shouldBe correlationId
    }
  }

  testWithRepo("correlationId is included in a targets role custom field") { implicit ns =>
    val targetUpdate = GenTargetUpdateRequest.generate
    val regDev = registerAdminDeviceOk()
    val deviceId = regDev.deviceId
    val correlationId = GenCorrelationId.generate
    createAssignmentOk(deviceId, regDev.primary.hardwareId, targetUpdate.some, correlationId.some)

    Get(apiUri(s"device/${deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val targets = responseAs[SignedPayload[TargetsRole]].signed

      targets.custom.flatMap(_.as[DeviceTargetsCustom].toOption.flatMap(_.correlationId)) should contain(correlationId)
    }
  }

  testWithRepo("ecu custom includes custom metadata") { implicit ns =>
    val targetUpdate = GenTargetUpdateRequest.retryUntil(_.to.uri.isDefined).generate
    val regDev = registerAdminDeviceOk()
    val deviceId = regDev.deviceId
    val correlationId = GenCorrelationId.generate
    createAssignmentOk(deviceId, regDev.primary.hardwareId, targetUpdate.some, correlationId.some)

    Get(apiUri(s"device/${deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val targets = responseAs[SignedPayload[TargetsRole]].signed
      val item = targets.targets.head._2
      val custom = item.custom.flatMap(_.as[TargetItemCustom].toOption)

      custom.map(_.ecuIdentifiers) should contain(Map(regDev.primary.ecuSerial -> TargetItemCustomEcuData(regDev.primary.hardwareId)))
      custom.flatMap(_.uri) shouldBe targetUpdate.to.uri
    }
  }

  testWithRepo("custom metadata includes targets per ecu when more than one ECU is assigned to the same target") { implicit ns =>
    val targetUpdate = GenTargetUpdateRequest.generate
    val regDev = registerAdminDeviceWithSecondariesOk()
    val (secondaryEcuSerial, secondaryEcu) = (regDev.ecus - regDev.primary.ecuSerial).head
    val deviceId = regDev.deviceId
    val correlationId = GenCorrelationId.generate
    createAssignmentOk(deviceId, regDev.primary.hardwareId, targetUpdate.some, correlationId.some)
    createAssignmentOk(deviceId, secondaryEcu.hardwareId, targetUpdate.some, correlationId.some)

    Get(apiUri(s"device/${deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      val targets = responseAs[SignedPayload[TargetsRole]].signed
      val item = targets.targets.head._2
      val custom = item.custom.flatMap(_.as[TargetItemCustom].toOption).map(_.ecuIdentifiers)

      custom.flatMap(_.get(regDev.primary.ecuSerial)) should contain (TargetItemCustomEcuData(regDev.primary.hardwareId))
      custom.flatMap(_.get(secondaryEcuSerial)) should contain (TargetItemCustomEcuData(secondaryEcu.hardwareId))
    }
  }

  testWithRepo("device gets logged when fetching root") { implicit ns =>
    val deviceId = registerDeviceOk()

    getDeviceRoleOk[RootRole](deviceId)

    val deviceSeenMsg = msgPub.wasReceived[DeviceSeen](deviceId.toString)
    deviceSeenMsg.map(_.namespace) should contain(ns)
  }

  testWithRepo("device gets logged when fetching root with version") { implicit ns =>
    val deviceId = registerDeviceOk()

    getDeviceRoleOk[RootRole](deviceId, version = 1.some)

    val deviceSeenMsg = msgPub.wasReceived[DeviceSeen](deviceId.toString)
    deviceSeenMsg.map(_.namespace) should contain(ns)
  }

  testWithRepo("publishes DeviceUpdateCompleted message") { implicit  ns =>
    val regDev = registerAdminDeviceOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val correlationId = GenCorrelationId.generate
    val installItem = InstallationItem(regDev.primary.ecuSerial, InstallationResult(true, ResultCode("pk-somecode"), ResultDescription("pk-somedesc")))
    val deviceReport = InstallationReport(correlationId, InstallationResult(true, ResultCode("somecode"), ResultDescription("somedesc")), Seq(installItem), None)
    val deviceManifest = buildPrimaryManifest(regDev.primary, regDev.primaryKey,targetUpdate.to, deviceReport.some)

    putManifestOk(regDev.deviceId, deviceManifest)

    val reportMsg = msgPub.wasReceived[DeviceUpdateEvent] { msg: DeviceUpdateEvent =>
      msg.deviceUuid === regDev.deviceId
    }.map(_.asInstanceOf[DeviceUpdateCompleted])

    reportMsg.map(_.namespace) should contain(ns)

    reportMsg.get.result shouldBe deviceReport.result
    val (ecuReportId, ecuReport) = reportMsg.get.ecuReports.head
    ecuReportId shouldBe regDev.primary.ecuSerial
    ecuReport.result shouldBe installItem.result
    reportMsg.get.correlationId shouldBe correlationId
  }

  testWithRepo("fails with EcuNotPrimary if device declares wrong primary") { implicit ns =>
    val regDev = registerAdminDeviceWithSecondariesOk()
    val targetUpdate = GenTargetUpdateRequest.generate
    val secondarySerial = regDev.secondaries.keys.head
    val secondaryKey = regDev.secondaryKeys(secondarySerial)
    val deviceManifest = buildSecondaryManifest(secondarySerial, regDev.primaryKey, secondarySerial, secondaryKey, Map(secondarySerial -> targetUpdate.to))

    putManifest(regDev.deviceId, deviceManifest) {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.Manifest.EcuNotPrimary
    }
  }
}
