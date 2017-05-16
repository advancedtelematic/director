package com.advancedtelematic.director.manifest

import akka.http.scaladsl.util.FastFuture
import cats.implicits._
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.DeviceRequest.OperationResult
import com.advancedtelematic.director.data.{LaunchedMultiTargetUpdateStatus, UpdateType}
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceUpdate,
  LaunchedMultiTargetUpdateRepositorySupport, UpdateTypesRepositorySupport}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

sealed abstract class DeviceManifestUpdateResult

final case class NoChange() extends DeviceManifestUpdateResult

final case class SuccessWithoutUpdateId() extends DeviceManifestUpdateResult

final case class SuccessWithUpdateId(namespace: Namespace, device: DeviceId, updateId: UpdateId,
                                     timestampVersion: Int, operations: Option[Seq[OperationResult]])
    extends DeviceManifestUpdateResult

final case class Failed(namespace: Namespace, device: DeviceId, currentTimestampVersion: Int,
                        operations: Option[Seq[OperationResult]])
    extends DeviceManifestUpdateResult


class AfterDeviceManifestUpdate(coreClient: CoreClient)
                               (implicit db: Database, ec: ExecutionContext)
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
          val operationResults = Seq(OperationResult("", 4, "Director and device not in sync"))
          await(clear(namespace, device, up, version, operations.getOrElse(operationResults)))
          await(rest.toList.traverse{case (up, version) => clear(namespace, device, up, version, operationResults)})
      }
    }
  }

  private def clear(namespace: Namespace, device: DeviceId, mUpdateId: Option[UpdateId],
                    version: Int, operations: Seq[OperationResult]): Future[Unit] = async {
    mUpdateId match {
      case None => Unit
      case Some(updateId) =>
        await(updateTypesRepository.getType(updateId)) match {
          case UpdateType.OLD_STYLE_CAMPAIGN =>
            await(coreClient.updateReport(namespace, device, updateId, operations))
          case UpdateType.MULTI_TARGET_UPDATE =>
            val status = LaunchedMultiTargetUpdateStatus.Failed
            await(launchedMultiTargetUpdateRepository.setStatus(device, updateId, version, status))
        }
    }
  }

  private def multiTargetUpdate(result: SuccessWithUpdateId): Future[Unit] = {
    val status = LaunchedMultiTargetUpdateStatus.Finished
    launchedMultiTargetUpdateRepository.setStatus(result.device, result.updateId, result.timestampVersion, status)
  }

  private def oldStyleCampaign(result: SuccessWithUpdateId): Future[Unit] = {
    val operations = result.operations.getOrElse {
      Seq(OperationResult("", 0, "Device did not report an operationresult, but is at the correct target"))
    }
    coreClient.updateReport(result.namespace, result.device, result.updateId, operations)
  }
}
