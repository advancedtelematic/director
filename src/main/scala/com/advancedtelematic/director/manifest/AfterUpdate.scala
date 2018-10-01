package com.advancedtelematic.director.manifest

import akka.http.scaladsl.util.FastFuture
import cats.implicits._
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.LaunchedMultiTargetUpdateStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceUpdate, LaunchedMultiTargetUpdateRepositorySupport}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.OperationResult
import com.advancedtelematic.libtuf_server.data.Messages.DeviceUpdateReport

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

sealed abstract class DeviceManifestUpdateResult

final case class NoChange() extends DeviceManifestUpdateResult

final case class SuccessWithoutUpdateId() extends DeviceManifestUpdateResult

final case class SuccessWithUpdateId(namespace: Namespace, device: DeviceId, updateId: UpdateId,
                                     timestampVersion: Int, operations: Option[Map[EcuSerial, OperationResult]])
    extends DeviceManifestUpdateResult

final case class Failed(namespace: Namespace, device: DeviceId, currentTimestampVersion: Int,
                        operations: Option[Map[EcuSerial, OperationResult]])
    extends DeviceManifestUpdateResult


class AfterDeviceManifestUpdate()
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport
    with LaunchedMultiTargetUpdateRepositorySupport {

  val OK_RESULT_CODE = 0
  val GENERAL_ERROR_RESULT_CODE = 19

  val report: DeviceManifestUpdateResult => Future[Unit] = {
    case NoChange() => FastFuture.successful(Unit)
    case SuccessWithoutUpdateId() => FastFuture.successful(())
    case res@SuccessWithUpdateId(namespace, device, updateId, timestampVersion, operations) =>
      clearMultiTargetUpdate(
        namespace, device, updateId, timestampVersion, operations.getOrElse(Map()),
        LaunchedMultiTargetUpdateStatus.Finished, UpdateStatus.Finished, OK_RESULT_CODE)
    case res@Failed(namespace, device, deviceVersion, operations) => async {
      var operationResults = operations.getOrElse(Map())
      val lastVersion = await(DeviceUpdate.clearTargetsFrom(namespace, device, deviceVersion, operationResults))
      val updatesToCancel = await(adminRepository.getUpdatesFromTo(namespace, device, deviceVersion, lastVersion))
        .filter { case (version, up) => up.isDefined }

      await(updatesToCancel.toList.traverse { case(version, updateId) =>
        val fut = clearMultiTargetUpdate(
          namespace, device, updateId.get, version, operationResults,
          LaunchedMultiTargetUpdateStatus.Failed, UpdateStatus.Failed, GENERAL_ERROR_RESULT_CODE)
        operationResults = Map()
        fut
      })

    }
  }

  private def clearMultiTargetUpdate(
      namespace: Namespace, device: DeviceId, updateId: UpdateId,
      version: Int, operations: Map[EcuSerial, OperationResult],
      launchedUpdateStatus: LaunchedMultiTargetUpdateStatus.Status,
      updateSpecStatus: UpdateStatus.UpdateStatus,
      resultCode: Int
    ): Future[Unit] = {
    for {
      _ <- launchedMultiTargetUpdateRepository.setStatus(device, updateId, version, launchedUpdateStatus)
      _ <- messageBusPublisher.publish(DeviceUpdateReport(namespace, device, updateId, version, operations, resultCode))
      _ <- messageBusPublisher.publish(UpdateSpec(namespace, device, updateSpecStatus))
    } yield ()
  }

}
