package com.advancedtelematic.service_blueprint.http

import akka.stream.Materializer
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext
import com.advancedtelematic.data.DataType._
import com.advancedtelematic.service_blueprint.db.BlueprintRepositorySupport
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import io.circe.generic.auto._


class BlueprintResource()
                 (implicit db: Database, ec: ExecutionContext, mat: Materializer) extends BlueprintRepositorySupport {

  import akka.http.scaladsl.server.Directives._

  val route =
    path("blueprint") {
      post {
        entity(as[Blueprint]) { blueprint =>
          val f = blueprintRepository.persist(blueprint)
          complete(f.map(_ => blueprint.id))
        }
      }
    } ~
    (get & path("blueprint" / Segment)) { id =>
      val f = blueprintRepository.find(id)
      complete(f)
    }
}
