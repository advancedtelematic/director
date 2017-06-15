package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.MultiTargetUpdateRequest
import com.advancedtelematic.director.db.MultiTargetUpdatesRepositorySupport
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
import com.advancedtelematic.libtuf.data.RefinedStringEncoding._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.ExecutionContext

class MultiTargetUpdatesResource(extractNamespace: Directive1[Namespace])(implicit db: Database, ec: ExecutionContext)
  extends MultiTargetUpdatesRepositorySupport {

  import Directives._

  def getTargetInfo(id: UpdateId, ns: Namespace): Route = {
    val f = multiTargetUpdatesRepository.fetch(id, ns).map { rows =>
      rows.map(mtuRow => mtuRow.hardwareId -> mtuRow.targetUpdateRequest).toMap
    }
    complete(f)
  }

  def createMultiTargetUpdate(ns: Namespace): Route = {
    entity(as[MultiTargetUpdateRequest]) { mtuRequest =>
      val updateId = UpdateId.generate
      val mtuRows = mtuRequest.multiTargetUpdateRows(updateId, ns)
      val f = multiTargetUpdatesRepository.create(mtuRows).map{ _ =>
        StatusCodes.Created -> updateId
      }
      complete(f)
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
