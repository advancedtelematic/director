package com.advancedtelematic.diff_service.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.advancedtelematic.diff_service.client.DiffServiceClient
import com.advancedtelematic.diff_service.data.DataType.{BsDiffQuery, CreateDiffInfoRequest, StaticDeltaQuery}
import com.advancedtelematic.diff_service.data.Codecs._
import com.advancedtelematic.diff_service.db.{BsDiffRepositorySupport, StaticDeltaRepositorySupport}
import com.advancedtelematic.libats.data.DataType.Namespace
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import scala.concurrent.ExecutionContext
import slick.jdbc.MySQLProfile.api.Database

class DiffResource(extractNamespace: Directive1[Namespace],
                   diffService: DiffServiceClient)
                  (implicit db: Database, ec: ExecutionContext)
    extends BsDiffRepositorySupport
    with StaticDeltaRepositorySupport
{

  def createDiff(ns: Namespace, diffRequests: Seq[CreateDiffInfoRequest]): Route = {
    val f = diffService.createDiffInfo(ns, diffRequests).map(StatusCodes.Created -> _)
    complete (f)
  }

  def findBsDiff(ns: Namespace, bsDiffQuery: BsDiffQuery): Route = {
    val f = bsdiffRepository.findResponse(ns, bsDiffQuery.from, bsDiffQuery.to)
    complete(f)
  }

  def findStaticDelta(ns: Namespace, staticDeltaQuery: StaticDeltaQuery): Route = {
    val f = staticDeltaRepository.findResponse(ns, staticDeltaQuery.from, staticDeltaQuery.to)
    complete(f)
  }

  val route: Route = extractNamespace { ns =>
    pathPrefix("diffs") {
      pathEnd {
        (post & entity(as[Seq[CreateDiffInfoRequest]])) { createRequest =>
          createDiff(ns, createRequest)
        }
      } ~
      path("bs_diff") {
        (get & entity(as[BsDiffQuery])) { bsDiffQuery =>
          findBsDiff(ns, bsDiffQuery)
        }
      } ~
      path("static_delta") {
        (get & entity(as[StaticDeltaQuery])) { staticDeltaQuery =>
          findStaticDelta(ns, staticDeltaQuery)
        }
      }
    }
  }
}
