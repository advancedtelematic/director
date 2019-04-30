package com.advancedtelematic.director.db

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.DeviceUpdateAssignment
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateEvent, DeviceUpdateCanceled}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

class CancelUpdate(implicit db: Database,
                   ec: ExecutionContext,
                   messageBusPublisher: MessageBusPublisher)
    extends DeviceUpdateAssignmentRepositorySupport
    with EcuUpdateAssignmentRepositorySupport
    with DeviceRepositorySupport {
  private def act(namespace: Namespace, device: DeviceId): DBIO[Option[DeviceUpdateAssignment]] = {
    for {
      current <- deviceRepository.getCurrentVersionAction(device).map(_.getOrElse(0))
      queuedItems = Schema.deviceUpdateAssignments.filter(_.deviceId === device).filter(_.version > current)
      // we can cancel if none of them have been served
      cantCancel <- queuedItems.map(_.served).filter(identity).exists.result
      res <- if (cantCancel) {
        DBIO.successful(None)
      } else for {
        latestVersion <- deviceUpdateAssignmentRepository.fetchLatestAction(namespace, device)
        assignment <- deviceUpdateAssignmentRepository.fetchAction(namespace, device, latestVersion)
        nextTimestampVersion = latestVersion + 1
        _ <- deviceUpdateAssignmentRepository.persistAction(namespace, device, None, None, nextTimestampVersion)
        _ <- deviceRepository.updateDeviceVersionAction(device, nextTimestampVersion)
      } yield Some(assignment)
    } yield res
  }.transactionally

  private def publishMessages(namespace: Namespace, assignment: DeviceUpdateAssignment): Future[Unit] = for {
    // UpdateSpec is deprecated by DeviceUpdateEvent
    _ <- messageBusPublisher.publish(UpdateSpec(namespace, assignment.deviceId, UpdateStatus.Canceled))
    _ <- assignment.correlationId.map { correlationId =>
      val deviceUpdateEvent: DeviceUpdateEvent = DeviceUpdateCanceled(
          namespace,
          Instant.now,
          correlationId,
          assignment.deviceId)
      messageBusPublisher.publish(deviceUpdateEvent)
    }.getOrElse(Future.successful(()))
  } yield ()

  /**
   * Cancels current update for the given device, publishes corresponding
   * messages, and returns the information about the cancelled update in case of
   * successful cancellation, otherwise fails with CouldNotCancelUpdate error
   */
  def one(ns: Namespace, device: DeviceId): Future[DeviceUpdateAssignment] =
    db.run(act(ns, device)).flatMap {
      case Some(assignment) =>
        publishMessages(ns, assignment).map(_ => assignment)
      case None =>
        FastFuture.failed(Errors.CouldNotCancelUpdate)
    }

  /**
   * Cancels current update for the given list of devices, publishes
   * corresponding messages for each cancelled device, and returns the
   * information about successfully cancelled devices. DOES NOT fail if a device
   * could not be cancelled.
   */
  def several(ns: Namespace, devices: Seq[DeviceId]): Future[Seq[DeviceUpdateAssignment]] = for {
    assignments <- db.run(DBIO.sequence(devices.map(act(ns,_))).map(_.flatten))
    _ <- Future.traverse(assignments)(assg => publishMessages(ns, assg))
  } yield assignments
}
