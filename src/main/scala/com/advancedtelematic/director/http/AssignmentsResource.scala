package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.AdminRequest.AssignUpdateRequest
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.db.{AdminRepositorySupport, CancelUpdate, SetMultiTargets}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateEvent, DeviceUpdateCanceled}

import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import java.time.Instant
import slick.jdbc.MySQLProfile.api.Database
import scala.concurrent.{ExecutionContext, Future}

class AssignmentsResource(extractNamespace: Directive1[Namespace])
(implicit db: Database, ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
  extends AdminRepositorySupport {

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
            val f = cancelUpdate.several(ns, devices).flatMap { canceledDevices =>
              Future.traverse(canceledDevices.filter(_.correlationId.isDefined)) { updateTarget =>
                for {
                  // UpdateSpec is deprecated by DeviceUpdateEvent
                  _ <- messageBusPublisher.publish(UpdateSpec(ns, updateTarget.device, UpdateStatus.Canceled))
                  deviceUpdateEvent: DeviceUpdateEvent = DeviceUpdateCanceled(
                      ns,
                      Instant.now,
                      updateTarget.correlationId.get,
                      updateTarget.device)
                  _ <- messageBusPublisher.publish(deviceUpdateEvent)
                } yield ()
              }.map(_ => canceledDevices.map(_.device))
            }
            complete(f)
          }
        }
      } ~
      pathPrefix(DeviceId.Path) { deviceId =>
        get {
          val f = adminRepository.findQueue(ns, deviceId)
          complete(f)
        } ~
        delete {
          val f = cancelUpdate.one(ns, deviceId).flatMap{ updateTarget =>
            for {
              // UpdateSpec is deprecated by DeviceUpdateEvent
              _ <- messageBusPublisher.publish(UpdateSpec(ns, updateTarget.device, UpdateStatus.Canceled))
              _ <- updateTarget.correlationId.map { correlationId =>
                val deviceUpdateEvent: DeviceUpdateEvent = DeviceUpdateCanceled(
                    ns,
                    Instant.now,
                    correlationId,
                    updateTarget.device)
                messageBusPublisher.publish(deviceUpdateEvent)
              }.getOrElse(Future.successful(()))
            } yield ()
          }
          complete(f)
        }
      }
    }
  }
}
