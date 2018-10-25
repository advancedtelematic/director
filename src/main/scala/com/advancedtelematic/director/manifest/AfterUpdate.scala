package com.advancedtelematic.director.manifest

import cats.implicits._
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceUpdate}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.OperationResult
import com.advancedtelematic.libtuf_server.data.Messages.DeviceUpdateReport

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
      namespace: Namespace, device: DeviceId, updateId: UpdateId,
      version: Int, operations: Map[EcuSerial, OperationResult]) : Future[Unit] =
      clearMultiTargetUpdate(
        namespace, device, updateId, version, operations,
        UpdateStatus.Finished, OK_RESULT_CODE)

  def failedMultiTargetUpdate(
      namespace: Namespace, device: DeviceId,
      version: Int, operations: Map[EcuSerial, OperationResult]) : Future[Unit] = async {

      val lastVersion = await(DeviceUpdate.clearTargetsFrom(namespace, device, version, operations))
      val updatesToCancel = await(adminRepository.getUpdatesFromTo(namespace, device, version, lastVersion))
        .filter { case (version, up) => up.isDefined }

      await(updatesToCancel.toList.traverse { case(version, updateId) =>
        clearMultiTargetUpdate(
          namespace, device, updateId.get, version, operations,
          UpdateStatus.Failed, GENERAL_ERROR_RESULT_CODE)
      })

    }

  private def clearMultiTargetUpdate(
      namespace: Namespace, device: DeviceId, updateId: UpdateId,
      version: Int, operations: Map[EcuSerial, OperationResult],
      updateSpecStatus: UpdateStatus.UpdateStatus,
      resultCode: Int
    ): Future[Unit] = {
    for {
      _ <- messageBusPublisher.publish(DeviceUpdateReport(namespace, device, updateId, version, operations, resultCode))
      _ <- messageBusPublisher.publish(UpdateSpec(namespace, device, updateSpecStatus))
    } yield ()
  }

}
