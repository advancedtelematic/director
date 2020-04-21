package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.RouteTestTimeout
import com.advancedtelematic.director.data.{AdminRequest, DeviceRequest, EdGenerators, KeyGenerators}
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.DataType.{MultiTargetUpdateRequest, TargetUpdateRequest}
import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.director.util.NamespaceTag.{Namespaced, NamespaceTag}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheDB}
import com.advancedtelematic.director.http.FileCacheSpec.Device
import com.advancedtelematic.libtuf.data.ClientDataType.TargetsRole
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import io.circe.{Decoder, Encoder}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libtuf.data.TufCodecs._
import org.scalatest.time.{Seconds, Span}
import cats.syntax.show._

object SingleDeviceUpdateSpec {
  final case class Device(id: DeviceId, primary: AdminRequest.RegisterEcu)
}

class SingleDeviceUpdateSpec extends DirectorSpec with KeyGenerators with DeviceRegistrationUtils with EdGenerators with FileCacheDB with DeviceRepositorySupport {
  import SingleDeviceUpdateSpec.Device

  override implicit val defaultTimeout = RouteTestTimeout(Span(500, Seconds))
  private[this] def updateManifest(deviceId: DeviceId, primaryEcu: EcuIdentifier)(implicit ns: NamespaceTag): Unit = {
    val ecuManifest = Seq(GenSignedEcuManifest(primaryEcu).generate)
    val devManifest = GenSignedDeviceManifest(primaryEcu, ecuManifest).generate

    updateManifestOk(deviceId, devManifest)
  }

  def isAvailable[T : Decoder : Encoder](device: DeviceId, file: String)(implicit ns: NamespaceTag): SignedPayload[T] = {
    Get(apiUri(s"device/${device.show}/$file")).namespaced ~> routes ~> check {
      val resp = responseAs[SignedPayload[T]]
      status shouldBe StatusCodes.OK
      resp
    }
  }

  private[this] def registerDevice()(implicit ns: NamespaceTag): Device = {
    val deviceId = DeviceId.generate

    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial

    val regDev = RegisterDevice(deviceId, primEcu, Seq(primEcuReg))
    registerDeviceOk(regDev)
    Device(deviceId, primEcuReg)
  }

  testWithNamespace("an update can be scheduled to a new device") { implicit ns  =>
    createRepo
    val Device(deviceId, primary) = registerDevice()

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtuRequest = MultiTargetUpdateRequest(Map(primary.hardwareId -> targetUpdate))
    val updateId = createMultiTargetUpdateOK(mtuRequest)
    scheduleOne(deviceId, updateId)
  }

  def createUpdate(device: Device)(implicit ns: NamespaceTag): (UpdateId, TargetUpdateRequest) = {
    val targetUpdate = GenTargetUpdateRequest.generate
    val mtuRequest = MultiTargetUpdateRequest(Map(device.primary.hardwareId -> targetUpdate))
    val updateId = createMultiTargetUpdateOK(mtuRequest)
    (updateId, targetUpdate)
  }

  def scheduleUpdate(device: Device)(implicit ns: NamespaceTag): (UpdateId, TargetUpdateRequest) = {
    val result @ (updateId, _) = createUpdate(device)
    scheduleOne(device.id, updateId)
    result
  }

  testWithNamespace("an update can be scheduled if metadata was rotated since last update") { implicit ns =>
    import com.advancedtelematic.libtuf.data.ClientCodecs._
    createRepo
    val device @ Device(deviceId, primary) = registerDevice()
    updateManifest(deviceId, primary.ecu_serial)

    val (_, firstUpdate) = scheduleUpdate(device)

    val ecuManifest: SignedPayload[DeviceRequest.EcuManifest] = GenSignedEcuManifestWithImage(primary.ecu_serial, firstUpdate.to.image).generate
    val devManifest = GenSignedDeviceManifest(primary.ecu_serial, Map(primary.ecu_serial -> ecuManifest)).generate
    updateManifestOk(deviceId, devManifest)

    deviceRepository.getCurrentVersion(deviceId).futureValue should equal(1)
    isAvailable[TargetsRole](deviceId, "targets.json").signed.version should equal(1)
    makeFilesExpire(deviceId).futureValue
    generateAllPendingFiles(Some(deviceId))
    scheduleUpdate(device)
  }

  testWithNamespace("an update can be scheduled if previous one was cancelled") { implicit ns =>
    import com.advancedtelematic.libtuf.data.ClientCodecs._
    createRepo
    val device @ Device(deviceId, primary) = registerDevice()
    updateManifest(deviceId, primary.ecu_serial)

    val (updateId, _) = scheduleUpdate(device)
    cancelDeviceOk(deviceId)
    scheduleUpdate(device)
  }

  testWithNamespace("an update can be scheduled if device sent weird manifest") { implicit ns =>
    createRepo
    val device @ Device(deviceId, primary) = registerDevice()
    updateManifest(deviceId, primary.ecu_serial)
    scheduleUpdate(device)

    val targetUpdate = GenTargetUpdateRequest.generate

    val ecuManifest: SignedPayload[DeviceRequest.EcuManifest] = GenSignedEcuManifestWithImage(primary.ecu_serial, targetUpdate.to.image).generate
    val devManifest = GenSignedDeviceManifest(primary.ecu_serial, Map(primary.ecu_serial -> ecuManifest)).generate
    updateManifestOk(deviceId, devManifest)
    scheduleUpdate(device)
  }

  testWithNamespace("fail to schedule an update if there is an active one") { implicit ns =>
    createRepo
    val device @ Device(deviceId, primary) = registerDevice()
    updateManifest(deviceId, primary.ecu_serial)
    scheduleUpdate(device)

    val (updateId, _) = createUpdate(device)
    Put(apiUri(s"admin/devices/${device.id.show}/multi_target_update/${updateId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
    }

  }

}
