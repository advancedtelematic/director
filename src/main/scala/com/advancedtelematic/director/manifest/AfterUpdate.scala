package com.advancedtelematic.director.manifest

import java.time.Instant

import cats.implicits._
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, DeviceUpdate}
import com.advancedtelematic.libats.data.DataType.{ResultCode, ResultDescription}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.InstallationResult
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateCompleted, DeviceUpdateEvent}
import slick.driver.MySQLDriver.api._

import scala.concurrent.{ExecutionContext, Future}


class AfterDeviceManifestUpdate()
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport
    with DeviceRepositorySupport {

  val canceledInstallationResult = InstallationResult(
    success = false,
    ResultCode("CANCELLED_ON_ERROR"),
    ResultDescription("Cancelled update due to previous installation error")
  )

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
      updatesToCancel <- adminRepository.getUpdatesFromTo(report.namespace, report.deviceUuid, nextVersion, lastVersion)
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
