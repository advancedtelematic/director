package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.db.{DbSignedRoleRepositorySupport, RepoNamespaceRepositorySupport}
import com.advancedtelematic.director.util.{DeviceManifestSpec, DirectorSpec, RepositorySpec, RouteResourceSpec}
import com.advancedtelematic.libtuf.data.TufDataType.TargetName
import cats.syntax.show._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libtuf.data.ClientCodecs._

class AutoUpdateResourceSpec extends DirectorSpec
  with RouteResourceSpec
  with AdminResources with RepositorySpec {

  testWithRepo("can create an auto update") { implicit ns =>
    val dev = registerAdminDeviceOk()
    val targetName = TargetName("mytarget")

    Put(apiUri(s"admin/devices/${dev.deviceId.show}/ecus/${dev.primary.ecuSerial.value}/auto_update/${targetName.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  testWithRepo("can remove an auto update") { implicit ns =>
    val dev = registerAdminDeviceOk()

    val targetName = TargetName("mytarget")

    Put(apiUri(s"admin/devices/${dev.deviceId.show}/ecus/${dev.primary.ecuSerial.value}/auto_update/${targetName.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Delete(apiUri(s"admin/devices/${dev.deviceId.show}/ecus/${dev.primary.ecuSerial.value}/auto_update/${targetName.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"admin/devices/${dev.deviceId.show}/ecus/${dev.primary.ecuSerial.value}/auto_update")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      responseAs[List[TargetName]] should be(empty)
    }
  }

  testWithRepo("gets all auto updates for a device") { implicit ns =>
    val dev = registerAdminDeviceOk()

    val targetName = TargetName("mytarget")

    Put(apiUri(s"admin/devices/${dev.deviceId.show}/ecus/${dev.primary.ecuSerial.value}/auto_update/${targetName.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"admin/devices/${dev.deviceId.show}/ecus/${dev.primary.ecuSerial.value}/auto_update")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK

      responseAs[List[TargetName]] should contain(targetName)
    }
  }
}
