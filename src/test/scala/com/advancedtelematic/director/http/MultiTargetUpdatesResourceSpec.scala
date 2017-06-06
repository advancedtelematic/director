package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType.MultiTargetUpdateDeltaRegistration
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
import org.scalacheck.Gen

class MultiTargetUpdatesResourceSpec extends DirectorSpec with DefaultPatience with ResourceSpec with Requests {


  test("fetching non-existent target info returns 404") {
    val id = UpdateId.generate()
    Get(apiUri(s"multi_target_updates/${id.uuid.toString}")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("can create and fetch multi-target updates") {
    val mtu = GenMultiTargetUpdateRequest.generate
    val id = createMultiTargetUpdateOK(mtu)

    fetchMultiTargetUpdate(id) shouldBe mtu.targets.mapValues(_.to.image)
  }

  test("can register deltas (if proper subset)") {
    val mtu = GenNMultiTargetUpdateRequest(10).generate
    val id = createMultiTargetUpdateOK(mtu)

    val delta = MultiTargetUpdateDeltaRegistration(
      Gen.someOf(mtu.targets.mapValues(_ => GenStaticDelta.generate)).generate.toMap
    )

    registerDeltaOk(id, delta)
  }

  test("deltas that are not subset fails") {
    val mtu = GenNMultiTargetUpdateRequest(10).generate
    val id = createMultiTargetUpdateOK(mtu)

    val delta = GenNMultiTargetUpdateDeltaRegistration(10).generate

    registerDeltaExpect(id, delta)(StatusCodes.PreconditionFailed)
  }

  test("can't register deltas twice") {
    val mtu = GenNMultiTargetUpdateRequest(10).generate
    val id = createMultiTargetUpdateOK(mtu)

    val delta = MultiTargetUpdateDeltaRegistration(
      (mtu.targets.mapValues(_ => GenStaticDelta.generate))
    )

    registerDeltaOk(id, delta)
    registerDeltaExpect(id, delta)(StatusCodes.Conflict)
  }
}
