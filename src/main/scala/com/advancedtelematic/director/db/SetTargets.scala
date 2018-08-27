package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.CustomImage
import com.advancedtelematic.director.data.UpdateType
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._


import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus

object SetTargets extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport
    with UpdateTypesRepositorySupport
    with ProcessedManifestsRepositorySupport {

  protected [db] def setDeviceTargetAction(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId], targets: Map[EcuSerial, CustomImage])
                                          (implicit db: Database, ec: ExecutionContext): DBIO[Int] = for {
    new_version <- adminRepository.updateTargetAction(namespace, device, updateId, targets)
    fcr = FileCacheRequest(namespace, new_version, device, updateId, FileCacheRequestStatus.PENDING, new_version)
    _ <- fileCacheRequestRepository.persistAction(fcr)
    _ <- adminRepository.updateDeviceTargetsAction(device, updateId, new_version)
    } yield new_version

  def setTargets(namespace: Namespace, devTargets: Seq[(DeviceId, SetTarget)], updateId: Option[UpdateId] = None)
                (implicit db: Database, ec: ExecutionContext): Future[Unit] = {

    def devAction(device: DeviceId, targets: SetTarget): DBIO[Int] =
      setDeviceTargetAction(namespace, device, updateId, targets.updates)

    val updateType: DBIO[Unit] = updateId match {
      case None => DBIO.successful(())
      case Some(updateId) =>
        updateTypesRepository.persistAction(updateId, UpdateType.OLD_STYLE_CAMPAIGN)
    }

    val updateDeviceTargets: Seq[DBIO[Int]] = devTargets.map { case (deviceId, setTarget) =>
      devAction(deviceId, setTarget).flatMap { _ =>
        // deleting the incoming manifest "cache" on targets change
        processedManifestsRepository.deleteAction(namespace, deviceId)
      }
    }

    val dbAct = DBIO.sequence(updateDeviceTargets)
                  .andThen(updateType)

    db.run(dbAct.transactionally)
  }
}
