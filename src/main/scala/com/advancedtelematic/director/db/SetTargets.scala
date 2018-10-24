package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.{CorrelationId, CustomImage}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._


import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus

object SetTargets extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport {

  protected [db] def setDeviceTargetAction(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId],
                                           targets: Map[EcuSerial, CustomImage],
                                           correlationId: Option[CorrelationId] = None)
                                          (implicit db: Database, ec: ExecutionContext): DBIO[Int] = for {
    new_version <- adminRepository.updateTargetAction(namespace, device, updateId, targets)
    fcr = FileCacheRequest(namespace, new_version, device,
                           FileCacheRequestStatus.PENDING, new_version, correlationId)
    _ <- fileCacheRequestRepository.persistAction(fcr)
    _ <- adminRepository.updateDeviceTargetsAction(device, updateId, new_version)
    } yield new_version

  def setTargets(namespace: Namespace, devTargets: Seq[(DeviceId, SetTarget)],
                 correlationId: Option[CorrelationId] = None)
                (implicit db: Database, ec: ExecutionContext): Future[Seq[Int]] = {
    def devAction(device: DeviceId, targets: SetTarget): DBIO[Int] =
      setDeviceTargetAction(namespace, device, None, targets.updates, correlationId)

    val dbAct = DBIO.sequence(devTargets.map((devAction _).tupled))
    db.run(dbAct.transactionally)
  }
}
