package com.advancedtelematic.director.http
import cats.syntax.show._
import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.AdminDataType.MultiTargetUpdate
import com.advancedtelematic.director.data.Generators
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.libats.data.ErrorRepresentation
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
import com.advancedtelematic.libats.data.ErrorCodes.MissingEntity
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.http.AdminResources

class MultiTargetUpdatesResourceSpec extends DirectorSpec
  with Generators with DefaultPatience with RouteResourceSpec with AdminResources {

  test("fetching non-existent target info returns 404") {
    val id = UpdateId.generate()

    Get(apiUri(s"multi_target_updates/${id.uuid.toString}")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
      responseAs[ErrorRepresentation].code shouldBe MissingEntity
    }
  }

  testWithNamespace("can GET multi-target updates") { implicit ns =>
    val mtu = createMtuOk()

    Get(apiUri(s"multi_target_updates/${mtu.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[MultiTargetUpdate]
    }
  }

  testWithNamespace("accepts mtu with an update") { implicit ns =>
    createMtuOk()
  }
}
