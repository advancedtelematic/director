package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{FileInfo, Image, MultiTargetUpdate, MultiTargetUpdateRequest, Namespace, UpdateId}
import com.advancedtelematic.director.db.MultiTargetUpdatesRepositorySupport
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import slick.driver.MySQLDriver.api.Database

import scala.async.Async._
import scala.concurrent.ExecutionContext

class MultiTargetUpdatesResource(extractNamespace: Directive1[Namespace])(implicit db: Database, ec: ExecutionContext)
  extends MultiTargetUpdatesRepositorySupport {

  import Directives._

  def getTargetInfo(id: UpdateId, ns: Namespace): Route = {
    val f = async {
      val rows = await(multiTargetUpdatesRepository.fetch(id, ns))
      rows.foldLeft(Map[String, Image]()) { (map, mtu) =>
        map + (mtu.hardwareId -> mtu.image)
      }
    }
    complete(f)
  }

  def createMultiTargetUpdate(ns: Namespace): Route = {
    entity(as[MultiTargetUpdateRequest]) { mtu =>
      val m = MultiTargetUpdate(mtu.id, mtu.hardwareId, mtu.target, mtu.checksum, mtu.targetLength, ns)
      complete(StatusCodes.Created -> multiTargetUpdatesRepository.create(m))
    }
  }

  val route = extractNamespace { ns =>
    pathPrefix("multi_target_updates") {
      (pathPrefix(UpdateId.Path) & get) { updateRequestId =>
        getTargetInfo(updateRequestId, ns)
      } ~
      (post & pathEnd) {
        createMultiTargetUpdate(ns)
      }
    }
  }
}
