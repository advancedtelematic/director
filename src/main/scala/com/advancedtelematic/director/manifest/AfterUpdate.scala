package com.advancedtelematic.director.manifest

import cats.implicits._
import com.advancedtelematic.director.data.Codecs.{encoderEcuSerial, encoderInstallationReport}
import com.advancedtelematic.director.data.DataType.CorrelationId
import com.advancedtelematic.director.data.DeviceRequest.InstallationReport
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceUpdate}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{Event, EventType, DeviceId, EcuSerial}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceEventMessage
import com.advancedtelematic.libtuf.data.TufDataType.OperationResult
import com.advancedtelematic.libtuf_server.data.Messages.DeviceUpdateReport

import io.circe.JsonObject
import io.circe.syntax._
import java.time.Instant
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._


class AfterDeviceManifestUpdate()
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport {

  val OK_RESULT_CODE = 0
  val GENERAL_ERROR_RESULT_CODE = 19

  def successMultiTargetUpdate(
      namespace: Namespace, device: DeviceId, correlationId: CorrelationId,
      version: Int, operations: Map[EcuSerial, OperationResult]) : Future[Unit] =
      clearMultiTargetUpdate(
        namespace, device, correlationId, version, operations,
        UpdateStatus.Finished, OK_RESULT_CODE)

  def failedMultiTargetUpdate(
      namespace: Namespace, device: DeviceId,
      version: Int, operations: Map[EcuSerial, OperationResult]) : Future[Unit] = async {

      val lastVersion = await(DeviceUpdate.clearTargetsFrom(namespace, device, version, operations))
      val updatesToCancel = await(adminRepository.getUpdatesFromTo(namespace, device, version, lastVersion))
        .filter { case (version, up) => up.isDefined }

      await(updatesToCancel.toList.traverse { case(version, correlationId) =>
        clearMultiTargetUpdate(
          namespace, device, correlationId.get, version, operations,
          UpdateStatus.Failed, GENERAL_ERROR_RESULT_CODE)
      })

    }

  private def clearMultiTargetUpdate(
      namespace: Namespace, device: DeviceId, correlationId: CorrelationId,
      version: Int, operations: Map[EcuSerial, OperationResult],
      updateSpecStatus: UpdateStatus.UpdateStatus,
      resultCode: Int
    ): Future[Unit] = {
    for {
      // _ <- messageBusPublisher.publish(DeviceUpdateReport(namespace, device, correlationId, version, operations, resultCode))
      _ <- messageBusPublisher.publish(UpdateSpec(namespace, device, updateSpecStatus))
      _ <- publishInstallationReport(
        namespace, device,
        InstallationReport(correlationId, resultCode, "", None))
      _ <- publishEcuInstallationReports(namespace, device, correlationId, operations)
    } yield ()
  }

  private def publishEcuInstallationReports(
      namespace: Namespace, device: DeviceId, correlationId: CorrelationId,
      operations: Map[EcuSerial, OperationResult]
    ): Future[Unit] = async {
      await(operations.toList.traverse { case (ecuSerial, opResult) =>
        publishEcuInstallationReport(namespace, device, ecuSerial, InstallationReport(correlationId, opResult.resultCode, opResult.resultText, None))
      })
  }

  private def publishEcuInstallationReport(
      namespace: Namespace, device: DeviceId,
      ecuSerial: EcuSerial, installationReport: InstallationReport
    ): Future[Unit] = {

    messageBusPublisher.publish(
        DeviceEventMessage(namespace, Event(
            device,
            java.util.UUID.randomUUID().toString(),
            EventType("EcuInstallationReport", 0),
            Instant.now, Instant.now,
            payload = installationReport.asJson.deepMerge(
              JsonObject("ecuSerial" -> ecuSerial.asJson).asJson))))
  }

  private def publishInstallationReport(
      namespace: Namespace, device: DeviceId, installationReport: InstallationReport
    ): Future[Unit] = {

    messageBusPublisher.publish(
        DeviceEventMessage(namespace, Event(
            device,
            java.util.UUID.randomUUID().toString(),
            EventType("InstallationReport", 0),
            Instant.now, Instant.now,
            payload = installationReport.asJson)))
  }

}
