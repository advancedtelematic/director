package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs.{assignUpdateRequestEncoder, targetsCustomDecoder}
import com.advancedtelematic.director.data.DataType.TargetsCustom
import com.advancedtelematic.director.data.AdminRequest.AssignUpdateRequest
import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.db.FileCacheDB
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.data.DataType.{CampaignId, MultiTargetUpdateId}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

trait AssignmentsResourceSpec extends DeviceUpdateSpec with FileCacheDB {

  def createDeviceWithAssignment()(implicit ns: NamespaceTag) = {
    createRepo().futureValue
    val deviceId = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val mtuId = createMtu(ahw -> ((bto, false)))
    val correlationId = CampaignId(mtuId.uuid)
    val req = AssignUpdateRequest(correlationId, Seq(deviceId), mtuId)

    Post(apiUri(s"assignments"), req).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]] shouldBe Seq(deviceId)
    }
    req
  }

  testWithNamespace("POST assignments sets correlationId") { implicit ns =>
    val AssignUpdateRequest(correlationId, Seq(deviceId), mtuId, _) = createDeviceWithAssignment()
    val targets = fetchTargetsFor(deviceId)
    targets.signed.custom.get.as[TargetsCustom].right.get shouldBe TargetsCustom(Some(correlationId))
  }

  testWithNamespace("PATCH assignments cancels assigned updates") { implicit ns =>
    val AssignUpdateRequest(correlationId, Seq(deviceId), mtuId, _) = createDeviceWithAssignment()

    Patch(apiUri(s"assignments"), Seq(deviceId)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val q = getAssignmentsOk(deviceId)
    q shouldBe Seq()
  }

  testWithNamespace("GET assignments/:device returns response with correlationId") { implicit ns =>
    val AssignUpdateRequest(correlationId, Seq(deviceId), mtuId, _) = createDeviceWithAssignment()

    val q = getAssignmentsOk(deviceId)
    q.map(_.correlationId) shouldBe Seq(Some(correlationId))
  }


  testWithNamespace("GET assignments/:device returns inFlight updates if the targets.json have been downloaded") { implicit ns =>
    val AssignUpdateRequest(correlationId, Seq(deviceId), mtuId, _) = createDeviceWithAssignment()

    val q = getAssignmentsOk(deviceId)
    q.map(_.inFlight) shouldBe Seq(false)

    fetchTargetsFor(deviceId)

    val q2 = getAssignmentsOk(deviceId)
    q2.map(_.inFlight) shouldBe Seq(true)
  }

  testWithNamespace("DELETE assignments/:device cancels assigned updates") { implicit ns =>
    val AssignUpdateRequest(correlationId, Seq(deviceId), mtuId, _) = createDeviceWithAssignment()

    Delete(apiUri(s"assignments/${deviceId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

    val q = getAssignmentsOk(deviceId)
    q shouldBe Seq()
  }

  // Test deprecated API
  testWithNamespace("PUT device/multi_target_update sets correlationId") { implicit ns =>
    createRepo().futureValue
    val device = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val updateId = createMtu(ahw -> ((bto, false)))
    scheduleOne(device, updateId)

    val targets = fetchTargetsFor(device)
    targets.signed.custom.get.as[TargetsCustom].right.get shouldBe TargetsCustom(Some(MultiTargetUpdateId(updateId.uuid)))
  }
}

class RsaAssignmentsResourceSpec extends AssignmentsResourceSpec with RsaGenerators

class EdAssignmentsResourceSpec extends AssignmentsResourceSpec with EdGenerators
