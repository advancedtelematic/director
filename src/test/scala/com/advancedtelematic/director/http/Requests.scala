package com.advancedtelematic.director.http

import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{EcuSerial, Image}
import com.advancedtelematic.director.data.DeviceRequest.DeviceManifest
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import org.genivi.sota.data.Uuid
import org.genivi.sota.marshalling.CirceMarshallingSupport._

trait Requests extends DirectorSpec with ResourceSpec {
  private def registerDevice(regDev: RegisterDevice): HttpRequest = Post(apiUri("admin"), regDev)

  def registerDeviceOk(regDev: RegisterDevice): Unit =
    registerDeviceOkWith(regDev, routes)

  def registerDeviceOkWith(regDev: RegisterDevice, withRoutes: Route): Unit =
    registerDevice(regDev) ~> withRoutes ~> check {
      status shouldBe StatusCodes.Created
    }

  def registerDeviceFail(regDev: RegisterDevice, expected: StatusCode): String = {
    registerDevice(regDev) ~> routes ~> check {
      status shouldBe expected
      responseAs[String]
    }
  }

  def updateManifest(manifest: SignedPayload[DeviceManifest]): HttpRequest =
    Put(apiUri("mydevice/manifest"), manifest)

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

  def getInstalledImages(device: Uuid): HttpRequest =
    Get(apiUri(s"admin/${device.show}/installed_images"))

  def getInstalledImagesOkWith(device: Uuid, withRoutes: Route): Seq[(EcuSerial, Image)] =
    getInstalledImages(device) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[(EcuSerial, Image)]]
    }

  def setTargets(device: Uuid, targets: SetTarget): HttpRequest =
    Put(apiUri(s"admin/${device.show}/set_targets"), targets)

  def setTargetsOk(device: Uuid, targets: SetTarget): Unit =
    setTargets(device, targets) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

}
