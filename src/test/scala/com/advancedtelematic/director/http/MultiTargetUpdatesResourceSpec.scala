package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Generators
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId

class MultiTargetUpdatesResourceSpec extends DirectorSpec with Generators with DefaultPatience with RouteResourceSpec with Requests {

  test("fetching non-existent target info returns 404") {
    val id = UpdateId.generate()
    Get(apiUri(s"multi_target_updates/${id.uuid.toString}")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("can create and fetch multi-target updates") {
    val mtu = GenMultiTargetUpdateRequest.generate
    val id = createMultiTargetUpdateOK(mtu)

    fetchMultiTargetUpdate(id) shouldBe mtu.targets
  }
}
