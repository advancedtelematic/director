package com.advancedtelematic.director.manifest

import cats.implicits._
import com.advancedtelematic.director.data.DataType.DeviceUpdateTarget
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceUpdate}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, InstallationResult, EcuInstallationReport}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename

import java.time.Instant
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._


class AfterDeviceManifestUpdate()
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport {

  val successInstallationResult = InstallationResult(true, "0", "All targeted ECUs were successfully updated")
  val failureInstallationResult = InstallationResult(false, "19", "One or more targeted ECUs failed to update")

  def successMultiTargetUpdate(
      namespace: Namespace, device: DeviceId,
      updateTarget: DeviceUpdateTarget,
      ecuResults: Map[EcuSerial, EcuInstallationReport]) : Future[Unit] =
    if(updateTarget.updateId.isDefined) {
      clearMultiTargetUpdate(
        namespace, device, updateTarget, successInstallationResult, ecuResults,
        UpdateStatus.Finished)
    } else {
      Future.successful(())
    }

  def failedMultiTargetUpdate(
      namespace: Namespace, device: DeviceId,
      version: Int,
      failedTargets: Map[EcuSerial, TargetFilename],
      ecuResults: Map[EcuSerial, EcuInstallationReport]) : Future[Unit] = async {

      val lastVersion = await(DeviceUpdate.clearTargetsFrom(namespace, device, version, failedTargets))
      val updatesToCancel = await(adminRepository.getUpdatesFromTo(namespace, device, version, lastVersion))
        .filter { _.updateId.isDefined }

      await(updatesToCancel.toList.traverse { case updateTarget =>
        clearMultiTargetUpdate(
          namespace, device, updateTarget, failureInstallationResult, ecuResults,
          UpdateStatus.Failed)
      })

    }

  private def clearMultiTargetUpdate(
      namespace: Namespace, device: DeviceId, updateTarget: DeviceUpdateTarget,
      result: InstallationResult,
      ecuResults: Map[EcuSerial, EcuInstallationReport],
      updateSpecStatus: UpdateStatus.UpdateStatus
    ): Future[Unit] = {
    for {
      _ <- messageBusPublisher.publish(
        DeviceInstallationReport(namespace, device, updateTarget.correlationId.get,
                                 result, ecuResults, report = None, Instant.now))
      _ <- messageBusPublisher.publish(UpdateSpec(namespace, device, updateSpecStatus))
    } yield ()
  }

}
