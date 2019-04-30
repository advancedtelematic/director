package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.AdminRequest.AssignUpdateRequest
import com.advancedtelematic.director.db.{EcuUpdateAssignmentRepositorySupport, CancelUpdate, SetMultiTargets}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId

import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api.Database
import scala.concurrent.ExecutionContext

class AssignmentsResource(extractNamespace: Directive1[Namespace])
(implicit val db: Database, val ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
  extends EcuUpdateAssignmentRepositorySupport {

  import Directives._

  val cancelUpdate = new CancelUpdate
  val setMultiTargets = new SetMultiTargets()

  val route = extractNamespace { ns =>
    pathPrefix("assignments") {
      pathEnd {
        post {
          entity(as[AssignUpdateRequest]) { req =>
            if (req.dryRun.getOrElse(false)) {
              complete {
                setMultiTargets.findAffected(ns, req.devices, req.mtuId)
              }
            } else {
              complete {
                setMultiTargets.setMultiUpdateTargetsForDevices(
                  ns, req.devices, req.mtuId, req.correlationId)
              }
            }
          }
        } ~
        patch {
          entity(as[Seq[DeviceId]]) { devices =>
            complete(cancelUpdate.several(ns, devices).map(_.map(_.deviceId)))
          }
        }
      } ~
      pathPrefix(DeviceId.Path) { deviceId =>
        get {
          val f = ecuUpdateAssignmentRepository.fetchQueue(ns, deviceId)
          complete(f)
        } ~
        delete {
          complete(cancelUpdate.one(ns, deviceId).map(_.deviceId))
        }
      }
    }
  }
}
