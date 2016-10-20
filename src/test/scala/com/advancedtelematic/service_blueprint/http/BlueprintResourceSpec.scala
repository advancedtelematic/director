package com.advancedtelematic.service_blueprint.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.data.DataType.Blueprint
import com.advancedtelematic.util.{ResourceSpec, ServiceBlueprintSpec}
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._

class BlueprintResourceSpec extends ServiceBlueprintSpec with ResourceSpec {
  test("POST creates, GET returns") {
    val blueprint = Blueprint("someblueprint", "somevalue")

    Post(apiUri("blueprint"), blueprint) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldBe blueprint.id
    }

    Get(apiUri("blueprint/someblueprint")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Blueprint] shouldBe blueprint
    }
  }
}
