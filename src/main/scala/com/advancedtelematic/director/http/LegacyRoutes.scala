package com.advancedtelematic.director.http

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, delete, path, put}
import akka.http.scaladsl.server.{Directive1, Route}
import com.advancedtelematic.libats.data.DataType.{MultiTargetUpdateId, Namespace}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateAssigned, DeviceUpdateEvent}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import akka.http.scaladsl.server.Directives._

import scala.concurrent.{ExecutionContext, Future}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import PaginationParametersDirectives._
import com.advancedtelematic.director.db.EcuRepositorySupport
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import io.circe.{Codec, Decoder, Encoder, Json}

object LegacyRoutes {
  sealed trait Director
  case object DirectorV1 extends Director
  case object DirectorV2 extends Director

  case class NamespaceDirectorChanged(namespace: Namespace, director: Director)

  import com.advancedtelematic.libats.codecs.CirceCodecs.{namespaceDecoder, namespaceEncoder}

  implicit val directorEncoder: Encoder[Director] = Encoder.instance {
    case DirectorV1 => Json.fromString("directorV1")
    case DirectorV2 => Json.fromString("directorV2")
  }
  implicit val directorDecoder: Decoder[Director] = Decoder.decodeString.map(_.toLowerCase).flatMap {
    case "directorv1" => Decoder.const(DirectorV1)
    case "directorv2" => Decoder.const(DirectorV2)
    case other => Decoder.failedWithMessage("Invalid value for `director`: " + other)
  }

  implicit val namespaceDirectorChangedCodec: Codec[NamespaceDirectorChanged] = io.circe.generic.semiauto.deriveCodec
  implicit val namespaceDirectorChangedMessageLike = MessageLike[NamespaceDirectorChanged](_.namespace.get)

  case class NamespaceDirectorChangeRequest(version: Director)

  implicit val namespaceDirectorChangeRequestCodec: Codec[NamespaceDirectorChangeRequest] = io.circe.generic.semiauto.deriveCodec
}

// Implements routes provided by old director that ota-web-app still uses
class LegacyRoutes(extractNamespace: Directive1[Namespace])(implicit val db: Database, val ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
  extends EcuRepositorySupport {
  import LegacyRoutes._

  private val deviceAssignments = new DeviceAssignments()

  private def createDeviceAssignment(ns: Namespace, deviceId: DeviceId, mtuId: UpdateId): Future[Unit] = {
    val correlationId = MultiTargetUpdateId(mtuId.uuid)
    val assignment = deviceAssignments.createForDevice(ns, correlationId, deviceId, mtuId)

    assignment.map { a =>
      val msg: DeviceUpdateEvent = DeviceUpdateAssigned(ns, Instant.now(), correlationId, a.deviceId)
      messageBusPublisher.publishSafe(msg)
    }
  }

  val route: Route =
    extractNamespace { ns =>
      path("admin" / "devices" / DeviceId.Path / "multi_target_update" / UpdateId.Path) { (deviceId, updateId) =>
        put {
          val f = createDeviceAssignment(ns, deviceId, updateId).map(_ => StatusCodes.OK)
          complete(f)
        }
      } ~
      path("assignments" / DeviceId.Path) { deviceId =>
        delete {
          val a = deviceAssignments.cancel(ns, List(deviceId))
          complete(a.map(_.map(_.deviceId)))
        }
      } ~
      (path("admin" / "devices") & PaginationParameters) { (limit, offset) =>
        get {
          complete(ecuRepository.findAllDeviceIds(ns, offset, limit))
        }
      } ~
      (path("admin" / "director") & entity(as[NamespaceDirectorChangeRequest])) { req =>
        val f = messageBusPublisher.publishSafe(NamespaceDirectorChanged(ns, req.version)).map(_ => StatusCodes.Accepted)
        complete(f)
      }
    }
}
