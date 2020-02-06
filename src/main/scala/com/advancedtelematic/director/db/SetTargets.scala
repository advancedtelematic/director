package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.{CustomImage, FileCacheRequest}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

object SetTargets extends DeviceUpdateAssignmentRepositorySupport
    with EcuUpdateAssignmentRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport {

  protected [db] def setDeviceTargetAction(namespace: Namespace, device: DeviceId, updateId: Option[UpdateId],
                                           targets: Map[EcuIdentifier, CustomImage],
                                           correlationId: Option[CorrelationId] = None)
                                          (implicit db: Database, ec: ExecutionContext): DBIO[Int] = for {
    new_version <- deviceUpdateAssignmentRepository.persistAction(namespace, device, correlationId, updateId)(fileCacheRepository)
    _ <- ecuUpdateAssignmentRepository.persistAction(namespace, device, new_version, targets)
    fcr = FileCacheRequest(namespace, new_version, device,
                           FileCacheRequestStatus.PENDING, new_version, correlationId)
    _ <- fileCacheRequestRepository.persistAction(fcr)
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
