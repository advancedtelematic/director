package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.{DeviceId, FileCacheRequest, UpdateId}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libats.data.Namespace

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._


object SetTargets extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport {

  def setTargets(namespace: Namespace, devTargets: Seq[(DeviceId, SetTarget)], updateId: Option[UpdateId] = None)
                (implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    def devAction(device: DeviceId, targets: SetTarget): DBIO[Unit] =
      adminRepository.updateTargetAction(namespace, device, updateId, targets.updates).flatMap { new_version =>
        val fcr = FileCacheRequest(namespace, new_version, device, FileCacheRequestStatus.PENDING, new_version)
        fileCacheRequestRepository.persistAction(fcr)
      }

    db.run {
      DBIO.sequence(devTargets.map((devAction _).tupled)).map(_ => ()).transactionally
    }
  }
}
