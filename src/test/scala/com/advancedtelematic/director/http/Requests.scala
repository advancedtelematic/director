package com.advancedtelematic.director.http

import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{DeviceId, EcuSerial, Image}
import com.advancedtelematic.director.data.DeviceRequest.DeviceManifest
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.codecs.AkkaCirce._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import de.heikoseeberger.akkahttpcirce.CirceSupport._

trait Requests extends DirectorSpec with ResourceSpec {
  private def registerDevice(regDev: RegisterDevice): HttpRequest = Post(apiUri("admin/devices"), regDev)

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

  def updateManifest(manifest: SignedPayload[DeviceManifest]): HttpRequest =
    Put(apiUri("device/manifest"), manifest)

  def updateManifestOk(manifest: SignedPayload[DeviceManifest]): Unit =
    updateManifestOkWith(manifest, routes)

  def updateManifestOkWith(manifest: SignedPayload[DeviceManifest], withRoutes: Route): Unit =
    updateManifest(manifest) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
    }

  def updateManifestExpect(manifest: SignedPayload[DeviceManifest], expected: StatusCode): Unit =
    updateManifest(manifest) ~> routes ~> check {
      status shouldBe expected
    }

  def getInstalledImages(device: DeviceId): HttpRequest =
    Get(apiUri(s"admin/${device.show}/images"))

  def getInstalledImagesOkWith(device: DeviceId, withRoutes: Route): Seq[(EcuSerial, Image)] =
    getInstalledImages(device) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[(EcuSerial, Image)]]
    }

  def setTargets(device: DeviceId, targets: SetTarget): HttpRequest =
    Put(apiUri(s"admin/${device.show}/targets"), targets)

  def setTargetsOk(device: DeviceId, targets: SetTarget): Unit =
    setTargets(device, targets) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
}
