package com.advancedtelematic.director.http

import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget, QueueResponse}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{Image, MultiTargetUpdateRequest}
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, LegacyDeviceManifest}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.codecs.AkkaCirce._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libtuf.data.RefinedStringEncoding._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, SignedPayload}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

trait Requests extends DirectorSpec with DefaultPatience with ResourceSpec {
  def registerDevice(regDev: RegisterDevice): HttpRequest = Post(apiUri("admin/devices"), regDev)

  def registerDeviceOk(regDev: RegisterDevice): Unit =
    registerDeviceOkWith(regDev, routes)

  def registerDeviceOkWith(regDev: RegisterDevice, withRoutes: Route): Unit =
    registerDevice(regDev) ~> withRoutes ~> check {
      status shouldBe StatusCodes.Created
    }

  def registerDeviceExpected(regDev: RegisterDevice, expected: StatusCode): Unit = {
    registerDevice(regDev) ~> routes ~> check {
      status shouldBe expected
    }
  }

  def updateLegacyManifestOk(device: DeviceId, manifest: SignedPayload[LegacyDeviceManifest]): Unit =
    Put(apiUri(s"device/${device.show}/manifest"), manifest) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def updateManifest(device: DeviceId, manifest: SignedPayload[DeviceManifest]): HttpRequest =
    Put(apiUri(s"device/${device.show}/manifest"), manifest)

  def updateManifestOk(device: DeviceId, manifest: SignedPayload[DeviceManifest]): Unit =
    updateManifestOkWith(device, manifest, routes)

  def updateManifestOkWith(device: DeviceId, manifest: SignedPayload[DeviceManifest], withRoutes: Route): Unit =
    updateManifest(device, manifest) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
    }

  def updateManifestExpect(device: DeviceId, manifest: SignedPayload[DeviceManifest], expected: StatusCode): Unit =
    updateManifest(device, manifest) ~> routes ~> check {
      status shouldBe expected
    }

  def getInstalledImages(device: DeviceId): HttpRequest =
    Get(apiUri(s"admin/devices/${device.show}/images"))

  def getInstalledImagesOkWith(device: DeviceId, withRoutes: Route): Seq[(EcuSerial, Image)] =
    getInstalledImages(device) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[(EcuSerial, Image)]]
    }

  def setTargets(device: DeviceId, targets: SetTarget): HttpRequest =
    Put(apiUri(s"admin/devices/${device.show}/targets"), targets)

  def setTargetsOk(device: DeviceId, targets: SetTarget): Unit =
    setTargets(device, targets) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def createMultiTargetUpdateOK(mtu: MultiTargetUpdateRequest): UpdateId =
    Post(apiUri(s"multi_target_updates"), mtu) ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

  def fetchMultiTargetUpdate(id: UpdateId): Map[HardwareIdentifier, Image] =
    Get(apiUri(s"multi_target_updates/${id.show}")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Map[HardwareIdentifier, Image]]
    }

  def deviceQueue(deviceId: DeviceId): HttpRequest =
    Get(apiUri(s"admin/devices/${deviceId.show}/queue"))

  def deviceQueueOk(deviceId: DeviceId)(implicit ns: NamespaceTag): Seq[QueueResponse] =
    deviceQueue(deviceId).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[QueueResponse]]
    }
}
