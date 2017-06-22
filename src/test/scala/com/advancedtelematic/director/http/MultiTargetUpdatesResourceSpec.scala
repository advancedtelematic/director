package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType.MultiTargetUpdateDiffRegistration
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

  test("can register diffs (if proper subset)") {
    val mtu = GenNMultiTargetUpdateRequest(10).generate
    val id = createMultiTargetUpdateOK(mtu)

    val diff = MultiTargetUpdateDiffRegistration(
      Gen.someOf(mtu.targets.mapValues(_ => GenDiffInfo.generate)).generate.toMap
    )

    registerDiffOk(id, diff)
  }

  test("diffs that are not subset fails") {
    val mtu = GenNMultiTargetUpdateRequest(10).generate
    val id = createMultiTargetUpdateOK(mtu)

    val diff = GenNMultiTargetUpdateDiffRegistration(10).generate

    registerDiffExpect(id, diff)(StatusCodes.PreconditionFailed)
  }

  test("can't register diffs twice") {
    val mtu = GenNMultiTargetUpdateRequest(10).generate
    val id = createMultiTargetUpdateOK(mtu)

    val diff = MultiTargetUpdateDiffRegistration(
      (mtu.targets.mapValues(_ => GenDiffInfo.generate))
    )

    registerDiffOk(id, diff)
    registerDiffExpect(id, diff)(StatusCodes.Conflict)
  }
}
