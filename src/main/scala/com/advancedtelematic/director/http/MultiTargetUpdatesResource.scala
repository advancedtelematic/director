package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{MultiTargetUpdateRequest, UpdateDevicesRequest}
import com.advancedtelematic.director.db.{MultiTargetUpdatesRepositorySupport, SetMultiTargets}
import com.advancedtelematic.libats.codecs.CirceRefined._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.ExecutionContext

class MultiTargetUpdatesResource(extractNamespace: Directive1[Namespace])
(implicit db: Database, ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
  extends MultiTargetUpdatesRepositorySupport {

  import Directives._

  val setMultiTargets = new SetMultiTargets()

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
      pathEnd {
        post {
          createMultiTargetUpdate(ns)
        }
      } ~
      pathPrefix(UpdateId.Path) { updateId =>
        pathEnd {
          get {
            getTargetInfo(updateId, ns)
          }
        } ~
        path("apply") {
          post {
            entity(as[UpdateDevicesRequest]) { req =>
              complete {
                setMultiTargets.setMultiUpdateTargetsForDevices(
                  ns, req.devices, updateId, req.correlationId)
              }
            }
          }
        }
      }
    }
  }
}
