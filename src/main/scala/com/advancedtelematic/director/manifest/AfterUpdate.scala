package com.advancedtelematic.director.manifest

import java.time.Instant

import cats.implicits._
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.db.{DeviceTargetRepositorySupport, DeviceRepositorySupport, DeviceUpdate}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.InstallationResult
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateEvent, DeviceUpdateCompleted}
import slick.driver.MySQLDriver.api._

import scala.concurrent.{ExecutionContext, Future}


class AfterDeviceManifestUpdate()
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends DeviceTargetRepositorySupport
    with DeviceRepositorySupport {

  val canceledInstallationResult = InstallationResult(false, "CANCELLED_ON_ERROR", "Cancelled update due to previous installation error")

  def clearUpdate(report: DeviceUpdateCompleted): Future[Unit] =
    for {
      _ <- publishReportEvent(report)
      _ <- if (!report.result.success) clearFailedUpdate(report) else Future.successful(())
    } yield ()

  private def clearFailedUpdate(report: DeviceUpdateCompleted) =
    for {
      version <- deviceRepository.getCurrentVersion(report.deviceUuid)
      lastVersion <- DeviceUpdate.clearTargetsFrom(report.namespace, report.deviceUuid, version)
      // TODO: Properly implement multiple updates queued per device.
      // Currently there can be only one update queued per device,
      // so the following code should not be run.
      nextVersion = version + 1
      updatesToCancel <- deviceTargetRepository.getUpdatesFromTo(report.namespace, report.deviceUuid, nextVersion, lastVersion)
      _ <- updatesToCancel.filter { _.correlationId.isDefined }.toList.traverse { case updateTarget =>
        publishReportEvent(
          DeviceUpdateCompleted(
            report.namespace,
            Instant.now,
            updateTarget.correlationId.get,
            report.deviceUuid,
            canceledInstallationResult,
            Map()))
      }
    } yield ()

  private def publishReportEvent(report: DeviceUpdateCompleted) = {
    for {
      // Support legacy UpdateSpec message
      _ <- messageBusPublisher.publish(UpdateSpec(
          report.namespace,
          report.deviceUuid,
          if(report.result.success) UpdateStatus.Finished else UpdateStatus.Failed))
      deviceUpdateEvent: DeviceUpdateEvent = report
      _ <- messageBusPublisher.publish(deviceUpdateEvent)
    } yield ()
  }

}
