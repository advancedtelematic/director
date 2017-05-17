package com.advancedtelematic.director.manifest

import akka.http.scaladsl.util.FastFuture
import cats.implicits._
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.DeviceRequest.{OperationResult => CoreOperationResult}
import com.advancedtelematic.director.data.{LaunchedMultiTargetUpdateStatus, UpdateType}
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceUpdate,
  LaunchedMultiTargetUpdateRepositorySupport, UpdateTypesRepositorySupport}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId, OperationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateReport

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


class AfterDeviceManifestUpdate(coreClient: CoreClient)
                               (implicit db: Database, ec: ExecutionContext,
                                messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport
    with LaunchedMultiTargetUpdateRepositorySupport
    with UpdateTypesRepositorySupport {

  val report: DeviceManifestUpdateResult => Future[Unit] = {
    case NoChange() => FastFuture.successful(Unit)
    case SuccessWithoutUpdateId() => FastFuture.successful(Unit)
    case res:SuccessWithUpdateId =>
      updateTypesRepository.getType(res.updateId).flatMap {
        case UpdateType.OLD_STYLE_CAMPAIGN  =>
          oldStyleCampaign(res)
        case UpdateType.MULTI_TARGET_UPDATE =>
          multiTargetUpdate(res)
      }
    case res@Failed(namespace, device, currentVersion, operations) => async {
      val lastVersion = await(DeviceUpdate.clearTargetsFrom(namespace, device, currentVersion))
      val updates:Seq[Option[UpdateId]] = await(adminRepository.getUpdatesFromTo(namespace, device, currentVersion, lastVersion))

      updates.zip(currentVersion to lastVersion) match {
        case Nil => Unit
        case (up, version) +: rest =>
          val operationResults: Map[EcuSerial, OperationResult] = Map()
          await(clear(namespace, device, up, version, operations.getOrElse(operationResults)))
          await(rest.toList.traverse{case (up, version) => clear(namespace, device, up, version, operationResults)})
      }
    }
  }

  private def toCoreOperationResult(updateId: UpdateId, operations: Map[EcuSerial, OperationResult]): Seq[CoreOperationResult] =
    operations.toSeq.map { case (ecu, op) =>
      CoreOperationResult(updateId.show, op.resultCode, op.resultText)
    }

  private def clear(namespace: Namespace, device: DeviceId, mUpdateId: Option[UpdateId],
                    version: Int, operations: Map[EcuSerial, OperationResult]): Future[Unit] = async {
    mUpdateId match {
      case None => Unit
      case Some(updateId) =>
        await(updateTypesRepository.getType(updateId)) match {
          case UpdateType.OLD_STYLE_CAMPAIGN =>
            await(clearOldStyleCampaign(namespace, device, updateId, operations))
          case UpdateType.MULTI_TARGET_UPDATE =>
            await(clearMultiTargetUpdate(namespace, device, updateId, version, operations))
        }
    }
  }

  private def clearMultiTargetUpdate(namespace: Namespace, device: DeviceId, updateId: UpdateId,
                                 version: Int, operations: Map[EcuSerial, OperationResult]): Future[Unit] = {
    val status = LaunchedMultiTargetUpdateStatus.Failed
    launchedMultiTargetUpdateRepository.setStatus(device, updateId, version, status).flatMap {_ =>
      messageBusPublisher.publish(DeviceUpdateReport(namespace, device, updateId, version, operations))
    }
  }

  private def clearOldStyleCampaign(namespace: Namespace, device: DeviceId, updateId: UpdateId,
                                operations: Map[EcuSerial, OperationResult]): Future[Unit] = {
    val coreOperations = toCoreOperationResult(updateId, operations) match {
      case Nil => Seq(CoreOperationResult("", 4, "Director and device not in sync"))
      case xs => xs
    }
    coreClient.updateReport(namespace, device, updateId, coreOperations)
  }

  private def multiTargetUpdate(result: SuccessWithUpdateId): Future[Unit] = {
    val status = LaunchedMultiTargetUpdateStatus.Finished
    launchedMultiTargetUpdateRepository.setStatus(result.device, result.updateId, result.timestampVersion, status).flatMap {_ =>
    messageBusPublisher.publish(DeviceUpdateReport(result.namespace, result.device, result.updateId,
                                                   result.timestampVersion, result.operations.getOrElse(Map())))
    }
  }

  private def oldStyleCampaign(result: SuccessWithUpdateId): Future[Unit] = {
    val operations: Seq[CoreOperationResult] = result.operations.map(toCoreOperationResult(result.updateId,_)).getOrElse {
      Seq(CoreOperationResult(result.updateId.show, 0, "Device did not report an operationresult, but is at the correct target"))
    }
    coreClient.updateReport(result.namespace, result.device, result.updateId, operations)
  }
}
