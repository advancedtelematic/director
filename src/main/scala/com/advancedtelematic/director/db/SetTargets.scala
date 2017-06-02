package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.{CustomImage, FileCacheRequest}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.data.UpdateType
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._


object SetTargets extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport
    with UpdateTypesRepositorySupport {

  protected [db] def deviceAction(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId], targets: Map[EcuSerial, CustomImage])
                          (implicit db: Database, ec: ExecutionContext): DBIO[Int] =
    adminRepository.updateTargetAction(namespace, device, updateId, targets).flatMap { new_version =>
      val fcr = FileCacheRequest(namespace, new_version, device, updateId, FileCacheRequestStatus.PENDING, new_version)
      fileCacheRequestRepository.persistAction(fcr).map(_ => new_version)
    }

  def setTargets(namespace: Namespace, devTargets: Seq[(DeviceId, SetTarget)], updateId: Option[UpdateId] = None)
                (implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    def devAction(device: DeviceId, targets: SetTarget): DBIO[Int] =
      deviceAction(namespace, device, updateId, targets.updates)

    val updateType: DBIO[Unit] = updateId match {
      case None => DBIO.successful(())
      case Some(updateId) =>
        updateTypesRepository.persistAction(updateId, UpdateType.OLD_STYLE_CAMPAIGN)
    }

    val dbAct = DBIO.sequence(devTargets.map((devAction _).tupled))
      .andThen(updateType)

    db.run(dbAct.transactionally)
  }
}
