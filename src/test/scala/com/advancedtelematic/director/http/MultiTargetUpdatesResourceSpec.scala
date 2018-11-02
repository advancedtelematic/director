package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs.{targetsCustomDecoder, updateDevicesRequestEncoder}
import com.advancedtelematic.director.data.DataType.{CorrelationId, TargetsCustom, UpdateDevicesRequest}
import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

trait MultiTargetUpdatesResourceSpec extends DeviceUpdateSpec {

  test("fetching non-existent target info returns 404") {
    val id = UpdateId.generate()
    Get(apiUri(s"multi_target_updates/${id.uuid.toString}")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  testWithNamespace("can create and fetch multi-target-update") { implicit ns =>
    val mtu = GenMultiTargetUpdateRequest.generate
    val id = createMultiTargetUpdateOK(mtu)

    fetchMultiTargetUpdate(id) shouldBe mtu.targets
  }

  testWithNamespace("POST multi_target_updates/:id/apply sets correlationId") { implicit ns =>
    createRepo().futureValue
    val deviceId = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val updateId = createMtu(ahw -> ((bto, false)))
    val correlationId = CorrelationId(s"here-ota:campaigns:$updateId")
    val req = UpdateDevicesRequest(correlationId, Seq(deviceId))

    Post(apiUri(s"multi_target_updates/${updateId.show}/apply"), req).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]] shouldBe Seq(deviceId)
    }

    val targets = fetchTargetsFor(deviceId)
    targets.signed.custom.get.as[TargetsCustom].right.get shouldBe TargetsCustom(Some(correlationId))
  }

  testWithNamespace("PUT device/multi_target_update sets correlationId") { implicit ns =>
    createRepo().futureValue
    val device = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val updateId = createMtu(ahw -> ((bto, false)))
    scheduleOne(device, updateId)

    val targets = fetchTargetsFor(device)
    targets.signed.custom.get.as[TargetsCustom].right.get shouldBe TargetsCustom(Some(CorrelationId.from(updateId)))
  }
}

class RsaMultiTargetUpdatesResourceSpec extends MultiTargetUpdatesResourceSpec with RsaGenerators

class EdMultiTargetUpdatesResourceSpec extends MultiTargetUpdatesResourceSpec with EdGenerators
