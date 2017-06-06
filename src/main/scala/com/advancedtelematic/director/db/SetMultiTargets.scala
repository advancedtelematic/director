package com.advancedtelematic.director.db

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.DataType.{CustomImage, HardwareIdentifier, LaunchedMultiTargetUpdate, MultiTargetUpdate, StaticDelta}
import com.advancedtelematic.director.data.LaunchedMultiTargetUpdateStatus
import com.advancedtelematic.director.data.UpdateType
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

object SetMultiTargets extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport
    with LaunchedMultiTargetUpdateRepositorySupport
    with UpdateTypesRepositorySupport {

  protected [db] def resolve(namespace: Namespace, device: DeviceId, hwRows: Seq[MultiTargetUpdate],
                             delta: Map[HardwareIdentifier, StaticDelta])
                            (implicit db: Database, ec: ExecutionContext): DBIO[Map[EcuSerial, CustomImage]] = {
    val hwTargets = hwRows.map(mtu => mtu.hardwareId -> ((mtu.fromTarget, Uri(), mtu.image))).toMap
    for {
      ecus <- adminRepository.fetchHwMappingAction(namespace, device)
    } yield ecus.mapValues { case (hw, oimage) =>
        hwTargets.get(hw).collect {
          case (None, uri, target) => CustomImage(target, hw, uri, delta.get(hw))
          case (Some(fromTarget), uri, target) if oimage.forall(_ == fromTarget.image)
              => CustomImage(target, hw, uri, delta.get(hw))
        }
    }.collect{ case (k, Some(v)) => k -> v}
  }

  protected [db] def checkMany(namespace: Namespace, devices: Seq[DeviceId], hwRows: Seq[MultiTargetUpdate])
                              (implicit db: Database, ec: ExecutionContext): DBIO[Seq[DeviceId]] = {
    def act(device: DeviceId): DBIO[Option[DeviceId]] = for {
      ecus <- adminRepository.fetchHwMappingAction(namespace, device)
      okay = hwRows.forall{ mtu =>
        ecus.exists {case (_, (hw, current)) =>
          hw == mtu.hardwareId && mtu.fromTarget.forall{ from =>
            current.exists{ cur => cur == from.image}
          }
        }
      }
    } yield if (okay) Some(device) else None

    for {
      devs <- adminRepository.devicesNotInACampaign(devices).result
      affected <- DBIO.sequence(devs.map(act)).map(_.collect{case Some(v) => v})
    } yield affected
  }

  def findAffected(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId)
                  (implicit db: Database, ec: ExecutionContext): Future[Seq[DeviceId]] = db.run {
    multiTargetUpdatesRepository.fetchAction(updateId, namespace).flatMap { hwRows =>
      checkMany(namespace, devices, hwRows)
    }
  }

  protected [db] def launchDeviceUpdate(namespace: Namespace, device: DeviceId, hwRows: Seq[MultiTargetUpdate],
                                        delta: Map[HardwareIdentifier, StaticDelta], updateId: UpdateId)
                                       (implicit db: Database, ec: ExecutionContext): DBIO[DeviceId] = {
    val dbAct = for {
      targets <- resolve(namespace, device, hwRows, delta)
      new_version <- SetTargets.deviceAction(namespace, device, Some(updateId), targets)
      _ <- launchedMultiTargetUpdateRepository.persistAction(LaunchedMultiTargetUpdate(device, updateId, new_version, LaunchedMultiTargetUpdateStatus.Pending))
      _ <- updateTypesRepository.persistAction(updateId, UpdateType.MULTI_TARGET_UPDATE)
    } yield device

    dbAct.transactionally
  }

  def setMultiUpdateTargets(namespace: Namespace, device: DeviceId, updateId: UpdateId)
                           (implicit db: Database, ec: ExecutionContext): Future[Unit] =
    setMultiUpdateTargetsForDevices(namespace, Seq(device), updateId).map(_ => Unit)

  def setMultiUpdateTargetsForDevices(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId)
                                     (implicit db: Database, ec: ExecutionContext): Future[Seq[DeviceId]] = db.run {
    val dbAct = for {
      hwRows <- multiTargetUpdatesRepository.fetchAction(updateId, namespace)
      toUpdate <- checkMany(namespace, devices, hwRows)
      delta <- multiTargetUpdatesRepository.fetchDeltasAction(updateId, namespace)
      _ <- DBIO.sequence(toUpdate.map{ device => launchDeviceUpdate(namespace, device, hwRows, delta, updateId)})
    } yield toUpdate

    dbAct.transactionally
  }
}
