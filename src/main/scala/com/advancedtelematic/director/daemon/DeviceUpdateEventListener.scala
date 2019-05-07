package com.advancedtelematic.director.daemon

import akka.Done
import com.advancedtelematic.director.db.{CancelUpdate, SetMultiTargets}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType._
import com.advancedtelematic.libats.messaging_datatype.Messages._
import java.util.UUID
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import slick.jdbc.MySQLProfile.api.Database

/**
 * Listens for DeviceUpdateEvents and performs assignment of multi-update
 * targets or cancellation of current device updates
 */
class DeviceUpdateEventListener(
      implicit db: Database,
      ec: ExecutionContext,
      messageBusPublisher: MessageBusPublisher)
    extends (DeviceUpdateEvent => Future[Done]) {

  def apply(message: DeviceUpdateEvent): Future[Done] = message match {

    case DeviceUpdateCancelRequested(ns, _, _, deviceId) =>
      cancelUpdate.one(ns, deviceId).map(_ => Done)

    case DeviceUpdateAssignmentRequested(ns, _, correlationId, deviceId, srcUpdateId) =>
      toUpdateId(srcUpdateId)
        .map { updateId =>
            setMultiTargets
              .setMultiUpdateTargets(ns, deviceId, updateId, correlationId)
              .map(_ => Done)
        }
        .getOrElse {
          log.warn(s"Ignoring DeviceUpdateAssignmentRequested message: unsupported SourceUpdateId: $srcUpdateId")
          Future.successful(Done)
        }

    case event =>
      log.debug(s"Ignoring unsupported event: $event")
      Future.successful(Done)
  }

  private def toUpdateId(sourceUpdateId: SourceUpdateId): Option[UpdateId] =
    Try(UpdateId(UUID.fromString(sourceUpdateId.value))).toOption

  private val log = LoggerFactory.getLogger(this.getClass)
  private val cancelUpdate = new CancelUpdate()
  private val setMultiTargets = new SetMultiTargets()
}
