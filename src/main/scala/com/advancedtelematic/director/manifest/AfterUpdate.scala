package com.advancedtelematic.director.manifest

import java.time.Instant

import cats.implicits._
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, DeviceUpdate}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.InstallationResult
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.libtuf.data.TufDataType.{TargetFilename, ValidTargetFilename}
import slick.driver.MySQLDriver.api._

import scala.concurrent.{ExecutionContext, Future}


class AfterDeviceManifestUpdate()
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport
    with DeviceRepositorySupport {

  val canceledInstallationResult = InstallationResult(false, "CANCELLED_ON_ERROR", "Cancelled update due to previous installation error")

  def clearUpdate(report: DeviceInstallationReport): Future[Unit] =
    for {
      _ <- publishReport(report)
      _ <- if (!report.result.success) clearFailedUpdate(report) else Future.successful(())
    } yield ()

  private def clearFailedUpdate(report: DeviceInstallationReport) =
    for {
      version <- deviceRepository.getCurrentVersion(report.device)
      failedTargets = toFailedTargets(report)
      lastVersion <- DeviceUpdate.clearTargetsFrom(report.namespace, report.device, version, failedTargets)
      // TODO: Properly implement multiple updates queued per device.
      // Currently there can be only one update queued per device,
      // so the following code should not be run.
      updatesToCancel <- adminRepository.getUpdatesFromTo(report.namespace, report.device, version, lastVersion)
      _ <- updatesToCancel.filter { _.correlationId.isDefined }.toList.traverse { case updateTarget =>
        publishReport(
          DeviceInstallationReport(
            report.namespace,
            report.device,
            updateTarget.correlationId.get,
            canceledInstallationResult,
            Map(), None, Instant.now))
      }
    } yield ()

  private def toFailedTargets(report: DeviceInstallationReport) : Map[EcuIdentifier, TargetFilename] = {
    report.ecuReports
      .filterNot(_._2.result.success)
      .mapValues { report => report.target.head.refineTry[ValidTargetFilename].get }
  }

  private def publishReport(report: DeviceInstallationReport) = {
    for {
      _ <- messageBusPublisher.publish(report)
      // Support legacy UpdateSpec message
      _ <- messageBusPublisher.publish(UpdateSpec(
          report.namespace,
          report.device,
          if(report.result.success) UpdateStatus.Finished else UpdateStatus.Failed))
    } yield ()
  }

}
