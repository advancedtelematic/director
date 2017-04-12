package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType.UpdateId
import com.advancedtelematic.director.db.MultiTargetUpdatesRepositorySupport
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}

class MultiTargetUpdatesResourceSpec extends DirectorSpec with DefaultPatience with ResourceSpec with Requests
  with MultiTargetUpdatesRepositorySupport {

  import com.advancedtelematic.director.data.GeneratorOps._

  test("fetching non-existent target info returns 404") {
    val id = UpdateId.generate()
    Get(apiUri(s"multi_target_updates/${id.uuid.toString}")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("can create and fetch multi-target updates") {
    val mtu = GenMultiTargetUpdateRequest.generate
    val id = createMultiTargetUpdateOK(mtu)

    fetchMultiTargetUpdate(id) shouldBe mtu.targets.mapValues(_.image)
  }
}
