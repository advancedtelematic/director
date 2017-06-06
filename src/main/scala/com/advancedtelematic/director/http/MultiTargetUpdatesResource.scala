package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{MultiTargetUpdate, MultiTargetUpdateRequest, MultiTargetUpdateDeltaRegistration}
import com.advancedtelematic.director.db.MultiTargetUpdatesRepositorySupport
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.ExecutionContext

class MultiTargetUpdatesResource(extractNamespace: Directive1[Namespace])(implicit db: Database, ec: ExecutionContext)
  extends MultiTargetUpdatesRepositorySupport {

  import Directives._

  def getTargetInfo(ns: Namespace, id: UpdateId): Route = {
    val f = multiTargetUpdatesRepository.fetch(id, ns).map { rows =>
      rows.map(mtu => mtu.hardwareId -> mtu.image).toMap
    }
    complete(f)
  }

  def createMultiTargetUpdate(ns: Namespace): Route = {
    entity(as[MultiTargetUpdateRequest]) { mtu =>
      val updateId = UpdateId.generate
      val f = multiTargetUpdatesRepository.create(MultiTargetUpdate(mtu, updateId, ns)).map(_ => updateId)
      complete(StatusCodes.Created -> f)
    }
  }

  def setStaticDelta(ns: Namespace, id: UpdateId): Route =
    entity(as[MultiTargetUpdateDeltaRegistration]) { deltaUpdates =>
      val f = multiTargetUpdatesRepository.setStaticDelta(ns, id, deltaUpdates.deltas.toSeq)
        .map (_ => ())
      complete(StatusCodes.NoContent -> f)
    }

  val route = extractNamespace { ns =>
    pathPrefix("multi_target_updates") {
      pathPrefix(UpdateId.Path) { updateRequestId =>
        get {
          getTargetInfo(ns, updateRequestId)
        } ~
        (put & path("static_delta")) {
          setStaticDelta(ns, updateRequestId)
        }
      } ~
      (post & pathEnd) {
        createMultiTargetUpdate(ns)
      }
    }
  }
}
