package com.advancedtelematic.director.http

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers.CsvSeq
import com.advancedtelematic.director.data.AdminDataType.AssignUpdateRequest
import com.advancedtelematic.director.data.AssignmentDataType.CancelAssignments
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceUpdateCanceledEncoder
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateAssigned, DeviceUpdateEvent}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.{ExecutionContext, Future}


class AssignmentsResource(extractNamespace: Directive1[Namespace])
                         (implicit val db: Database, val ec: ExecutionContext, messageBusPublisher: MessageBusPublisher) {

  import Directives._

  val deviceAssignments = new DeviceAssignments()

  private def createAssignments(ns: Namespace, req: AssignUpdateRequest): Future[Unit] = {
    val assignments = deviceAssignments.createForDevices(ns, req.correlationId, req.devices, req.mtuId)

    assignments.map {
      _.foreach { a =>
        val msg: DeviceUpdateEvent = DeviceUpdateAssigned(ns, Instant.now(), req.correlationId, a.deviceId)
        messageBusPublisher.publishSafe(msg)
      }
    }
  }

  private implicit val updateIdUnmarshaller = UpdateId.unmarshaller
  private implicit val deviceIdUnmarshaller = DeviceId.unmarshaller

  val route = extractNamespace { ns =>
    pathPrefix("assignments") {
      (path("devices") & parameter('mtuId.as[UpdateId]) & parameter('ids.as(CsvSeq[DeviceId]))) { (mtuId, deviceIds) =>
        val f = deviceAssignments.findAffectedDevices(ns, deviceIds, mtuId)
        complete(f)
      } ~
      pathEnd {
        post {
          entity(as[AssignUpdateRequest]) { req =>
            val f = createAssignments(ns, req).map(_ => StatusCodes.Created)
            complete(f)
          }
        } ~
        patch {
          entity(as[CancelAssignments]) { cancelAssignments =>
            val a = deviceAssignments.cancel(ns, cancelAssignments.cancelAssignments)
            complete(a.map(_.map(_.deviceId)))
          }
        }
      } ~
      path(DeviceId.Path) { deviceId =>
        get { //  This should be replacing /queue in /admin
          val f = deviceAssignments.findDeviceAssignments(ns, deviceId)
          complete(f)
        }
      }
    }
  }
}
