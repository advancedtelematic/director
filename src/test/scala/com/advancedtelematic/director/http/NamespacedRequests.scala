package com.advancedtelematic.director.http

import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{Image, MultiTargetUpdateRequest, TargetUpdateRequest}
import com.advancedtelematic.director.data.Legacy.LegacyDeviceManifest
import com.advancedtelematic.director.data.TestCodecs._
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libtuf.data.ClientDataType.{RootRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, KeyType, RepoId, SignedPayload, TargetFilename, TargetName, TufKey}
import com.advancedtelematic.libtuf_server.data.Requests.CreateRepositoryRequest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import io.circe.syntax._

trait NamespacedRequests extends DirectorSpec with DefaultPatience with RouteResourceSpec {

  private def checkPagination[U](limit: Option[Long], offset: Option[Long], pag: PaginationResult[U]): PaginationResult[U] = {
    pag.limit shouldBe limit.getOrElse(50L).min(1000)
    pag.offset shouldBe offset.getOrElse(0L)
    (pag.values.length <= pag.limit) shouldBe true
    pag.values.length shouldBe scala.math.max(0, scala.math.min(pag.total - pag.offset, pag.limit))
    pag
  }

  def registerDevice(regDev: RegisterDevice)(implicit ns: NamespaceTag): HttpRequest =
    Post(apiUri("admin/devices"), regDev).namespaced

  def registerDeviceOk(regDev: RegisterDevice)(implicit ns: NamespaceTag): Unit =
    registerDeviceOkWith(regDev, routes)

  def registerDeviceOkWith(regDev: RegisterDevice, withRoutes: Route)(implicit ns: NamespaceTag): Unit =
    registerDevice(regDev) ~> withRoutes ~> check {
      status shouldBe StatusCodes.Created
    }

  def registerDeviceExpected(regDev: RegisterDevice, expected: StatusCode)(implicit ns: NamespaceTag): Unit = {
    registerDevice(regDev) ~> routes ~> check {
      status shouldBe expected
    }
  }

  def updateLegacyManifestOk(device: DeviceId, manifest: SignedPayload[LegacyDeviceManifest])
                            (implicit ns: NamespaceTag): Unit =
    Put(apiUri(s"device/${device.show}/manifest"), manifest).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def updateManifest(device: DeviceId, manifest: SignedPayload[Json])(implicit ns: NamespaceTag): HttpRequest =
    Put(apiUri(s"device/${device.show}/manifest"), manifest).namespaced

  def updateManifestOk(device: DeviceId, manifest: SignedPayload[Json])(implicit ns: NamespaceTag): Unit =
    updateManifestOkWith(device, manifest, routes)

  def updateManifestOkWith(device: DeviceId, manifest: SignedPayload[Json], withRoutes: Route)(implicit ns: NamespaceTag): Unit = {
    updateManifest(device, manifest) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  def updateManifestExpect(device: DeviceId, manifest: SignedPayload[Json], expected: StatusCode)(implicit ns: NamespaceTag): Unit =
    updateManifest(device, manifest) ~> routes ~> check {
      status shouldBe expected
    }

  def getInstalledImages(device: DeviceId)(implicit ns: NamespaceTag): HttpRequest =
    Get(apiUri(s"admin/devices/${device.show}/images")).namespaced

  def getInstalledImagesOkWith(device: DeviceId, withRoutes: Route)(implicit ns: NamespaceTag): Seq[(EcuSerial, Image)] =
    getInstalledImages(device) ~> withRoutes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[(EcuSerial, Image)]]
    }

  def createMultiTargetUpdateOK(mtu: MultiTargetUpdateRequest)(implicit ns: NamespaceTag): UpdateId =
    Post(apiUri(s"multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

  def fetchMultiTargetUpdate(id: UpdateId)(implicit ns: NamespaceTag): Map[HardwareIdentifier, TargetUpdateRequest] =
    Get(apiUri(s"multi_target_updates/${id.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Map[HardwareIdentifier, TargetUpdateRequest]]
    }

  def fetchTimestampFor(device: DeviceId)(implicit ns: NamespaceTag): SignedPayload[TimestampRole] = {
    Get(apiUri(s"device/${device.show}/timestamp.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TimestampRole]]
    }
  }

  def fetchTargetsFor(device: DeviceId)(implicit ns: NamespaceTag): SignedPayload[TargetsRole] = {
    Get(apiUri(s"device/${device.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }
  }

  def fetchRootFor(device: DeviceId)(implicit ns: NamespaceTag): SignedPayload[RootRole] =
    Get(apiUri(s"device/${device.show}/root.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]]
    }

  def fetchRootFor(device: DeviceId, version: Int)(implicit ns: NamespaceTag): SignedPayload[RootRole] =
    Get(apiUri(s"device/${device.show}/$version.root.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]]
    }

  def setTargets(device: DeviceId, targets: SetTarget)(implicit ns: NamespaceTag): HttpRequest =
    Put(apiUri(s"admin/devices/${device.show}/targets"), targets).namespaced

  def setTargetsOk(device: DeviceId, targets: SetTarget)(implicit ns: NamespaceTag): Unit =
    setTargets(device, targets) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }


  def scheduleOne(device: DeviceId, updateId: UpdateId)(implicit ns: NamespaceTag): Unit =
    Put(apiUri(s"admin/devices/${device.show}/multi_target_update/${updateId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def launchMtu(updateId: UpdateId, devices: Seq[DeviceId])(implicit ns: NamespaceTag): Seq[DeviceId] =
    Put(apiUri(s"admin/multi_target_updates/${updateId.show}"), devices).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val affected = responseAs[Seq[DeviceId]]
      affected
    }

  def findByUpdate(updateId: UpdateId)(implicit ns: NamespaceTag): MultiTargetUpdateRequest =
    Get(apiUri(s"admin/multi_target_updates/${updateId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[MultiTargetUpdateRequest]
    }

  def findAffectedByUpdate(updateId: UpdateId, devices: Seq[DeviceId])(implicit ns: NamespaceTag): Seq[DeviceId] =
    Get(apiUri(s"admin/multi_target_updates/${updateId.show}/affected"), devices).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]]
    }

  def getAffectedByImage(filepath: String) (limit: Option[Long]= None, offset: Option[Long] = None)
                        (implicit ns: NamespaceTag): PaginationResult[DeviceId] = {
    val query = Uri.Query(limit.map("limit" -> _.toString).toMap ++ offset.map("offset" -> _.toString).toMap)
    val entity = Json.obj("filepath" -> filepath.asJson)

    Get(Uri(apiUri(s"admin/images/affected")).withQuery(query), entity).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      checkPagination(limit, offset, responseAs[PaginationResult[DeviceId]])
    }
  }

  def findDevices(limit: Option[Long]= None, offset: Option[Long] = None)
                 (implicit ns: NamespaceTag): PaginationResult[DeviceId] = {
    val query = Uri.Query(limit.map("limit" -> _.toString).toMap ++ offset.map("offset" -> _.toString).toMap)

    Get(Uri(apiUri(s"admin/devices")).withQuery(query)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      checkPagination(limit, offset, responseAs[PaginationResult[DeviceId]])
    }
  }


  def getHw(limit: Option[Long]= None, offset: Option[Long] = None)
           (implicit ns: NamespaceTag): PaginationResult[HardwareIdentifier] = {
    val query = Uri.Query(limit.map("limit" -> _.toString).toMap ++ offset.map("offset" -> _.toString).toMap)

    Get(Uri(apiUri(s"admin/devices/hardware_identifiers")).withQuery(query)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      checkPagination(limit, offset, responseAs[PaginationResult[HardwareIdentifier]])
    }
  }


  def deviceQueue(deviceId: DeviceId)(implicit ns: NamespaceTag): HttpRequest =
    Get(apiUri(s"admin/devices/${deviceId.show}/queue")).namespaced

  def deviceQueueOk(deviceId: DeviceId)(implicit ns: NamespaceTag): Seq[QueueResponse] =
    deviceQueue(deviceId) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[QueueResponse]]
    }

  def findDeviceOk(device: DeviceId)(implicit ns: NamespaceTag): Seq[EcuInfoResponse] = {
    Get(apiUri(s"admin/devices/${device.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[EcuInfoResponse]]
    }
  }

  def findPublicKeyOk(device: DeviceId, ecuSerial: EcuSerial)(implicit ns: NamespaceTag): TufKey = {
    Get(Uri(apiUri(s"admin/devices/${device.show}/ecus/public_key"))
          .withQuery(Uri.Query("ecu_serial" -> ecuSerial.value))).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[TufKey]
    }
  }

  def createRepo(implicit ns: NamespaceTag): RepoId =
    Post(apiUri("admin/repo")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[RepoId]
    }

  def createRepo(keyType: KeyType)(implicit ns: NamespaceTag): RepoId =
    Post(apiUri("admin/repo"), CreateRepositoryRequest(keyType)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[RepoId]
    }

  def fetchRootKeyType(implicit ns: NamespaceTag): KeyType =
    fetchRootOk.signed.keys.head._2.keytype

  def createRepoOk(keyType: KeyType)(implicit ns: NamespaceTag): Unit = {
    createRepo(keyType)
    fetchRootKeyType shouldBe keyType
  }

  def fetchRoot(implicit ns: NamespaceTag): HttpRequest =
    Get(apiUri("admin/repo/root.json")).namespaced

  def fetchRoot(version: Int)(implicit ns: NamespaceTag): HttpRequest =
    Get(apiUri(s"admin/repo/$version.root.json")).namespaced

  def fetchRootOk(implicit ns: NamespaceTag): SignedPayload[RootRole] =
    fetchRoot ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]]
    }

  def fetchRootOk(version: Int)(implicit ns: NamespaceTag): SignedPayload[RootRole] =
    fetchRoot(version) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]]
    }

  def getCountInstalledImages(filepaths: Seq[TargetFilename])
                             (implicit ns: NamespaceTag): Map[TargetFilename, Int] = {
    Post(Uri(apiUri(s"admin/images/installed_count")), FindImageCount(filepaths)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Map[TargetFilename, Int]]
    }
  }

  def findAutoUpdate(device: DeviceId, ecuSerial: EcuSerial)(implicit ns: NamespaceTag): Seq[TargetName] =
    Get(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[TargetName]]
    }

  def deleteAllAutoUpdate(device: DeviceId, ecuSerial: EcuSerial)(implicit ns: NamespaceTag): Unit =
    Delete(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def setAutoUpdate(device: DeviceId, ecuSerial: EcuSerial, target: TargetName)(implicit ns: NamespaceTag): Unit = {
    Put(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update/${target.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  def deleteAutoUpdate(device: DeviceId, ecuSerial: EcuSerial, target: TargetName)(implicit ns: NamespaceTag): Unit = {
    Delete(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update/${target.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  def cancelDevice(device: DeviceId)(implicit ns:NamespaceTag): HttpRequest =
    Put(apiUri(s"admin/devices/${device.show}/queue/cancel")).namespaced

  def cancelDeviceOk(device: DeviceId)(implicit ns:NamespaceTag): Unit =
    cancelDevice(device) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def cancelDeviceFail(device: DeviceId)(implicit ns:NamespaceTag): Unit =
    cancelDevice(device) ~> routes ~> check {
      status shouldBe StatusCodes.PreconditionFailed
    }

  def cancelDevices(devices: DeviceId*)(implicit ns: NamespaceTag): Seq[DeviceId] =
    Put(apiUri(s"admin/devices/queue/cancel"), devices).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]]
    }
}
