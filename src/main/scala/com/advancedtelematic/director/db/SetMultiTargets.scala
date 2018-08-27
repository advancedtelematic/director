package com.advancedtelematic.director.db

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.{CustomImage, LaunchedMultiTargetUpdate, MultiTargetUpdateRow}
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.{LaunchedMultiTargetUpdateStatus, UpdateType}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class SetMultiTargets()(implicit messageBusPublisher: MessageBusPublisher) extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport
    with LaunchedMultiTargetUpdateRepositorySupport
    with UpdateTypesRepositorySupport {

  protected [db] def resolve(namespace: Namespace, device: DeviceId, mtuRows: Seq[MultiTargetUpdateRow])
                            (implicit db: Database, ec: ExecutionContext): DBIO[Map[EcuSerial, CustomImage]] = {
    val hwTargets = mtuRows.map{ mtu =>
      val diffFormat = if (mtu.generateDiff) Some(mtu.targetFormat) else None
      mtu.hardwareId -> ((mtu.fromTarget, CustomImage(mtu.toTarget.image, Uri(), diffFormat)))
    }.toMap
    for {
      ecus <- adminRepository.fetchHwMappingAction(namespace, device)
    } yield ecus.mapValues { case (hw, currentImage) =>
        hwTargets.get(hw).collect {
          case (None, toTarget) => toTarget
          case (Some(fromCond), toTarget) if currentImage.contains(fromCond.image) => toTarget
        }
    }.collect{ case (k, Some(v)) => k -> v}
  }

  protected [db] def checkDevicesSupportUpdates(namespace: Namespace, devices: Seq[DeviceId], mtuRows: Seq[MultiTargetUpdateRow])
                                               (implicit db: Database, ec: ExecutionContext): DBIO[Seq[DeviceId]] = {
    def act(device: DeviceId): DBIO[Option[DeviceId]] = for {
      ecus <- adminRepository.fetchHwMappingAction(namespace, device)
      okay = mtuRows.forall{ mtu =>
        ecus.exists {case (_, (hw, current)) =>
          hw == mtu.hardwareId && mtu.fromTarget.forall{ from =>
            current.contains(from.image)
          }
        }
      }
    } yield if (okay) Some(device) else None

    for {
      devs <- adminRepository.devicesNotInACampaign(devices).result
      affected <- DBIO.sequence(devs.map(act)).map(_.flatten)
    } yield affected
  }

  def findAffected(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId)
                  (implicit db: Database, ec: ExecutionContext): Future[Seq[DeviceId]] = db.run {
    multiTargetUpdatesRepository.fetchAction(updateId, namespace).flatMap { hwRows =>
      checkDevicesSupportUpdates(namespace, devices, hwRows)
    }
  }

  protected [db] def launchDeviceUpdate(namespace: Namespace, device: DeviceId, hwRows: Seq[MultiTargetUpdateRow], updateId: UpdateId)
                                       (implicit db: Database, ec: ExecutionContext): DBIO[DeviceId] = {
    val dbAct = for {
      targets <- resolve(namespace, device, hwRows)
      new_version <- SetTargets.setDeviceTargetAction(namespace, device, Some(updateId), targets)
      _ <- launchedMultiTargetUpdateRepository.persistAction(LaunchedMultiTargetUpdate(device, updateId, new_version, LaunchedMultiTargetUpdateStatus.Pending))
    } yield device

    dbAct.transactionally
  }

  def getMultiTargetUpdates(namespace: Namespace, updateId: UpdateId)(implicit db: Database, ec: ExecutionContext): Future[Seq[MultiTargetUpdateRow]] =
    multiTargetUpdatesRepository.fetch(updateId, namespace)

  def setMultiUpdateTargets(namespace: Namespace, device: DeviceId, updateId: UpdateId)
                           (implicit db: Database, ec: ExecutionContext): Future[Unit] =
    setMultiUpdateTargetsForDevices(namespace, Seq(device), updateId).flatMap {
      case Seq(d) => FastFuture.successful(())
      case _ => FastFuture.failed(Errors.CouldNotScheduleDevice)
    }

  def setMultiUpdateTargetsForDevices(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId)
                                     (implicit db: Database, ec: ExecutionContext): Future[Seq[DeviceId]] = {
    val dbAct = for {
      hwRows <- multiTargetUpdatesRepository.fetchAction(updateId, namespace)
      toUpdate <- checkDevicesSupportUpdates(namespace, devices, hwRows)
      _ <- DBIO.sequence(toUpdate.map{ device => launchDeviceUpdate(namespace, device, hwRows, updateId)})
      _ <- updateTypesRepository.persistAction(updateId, UpdateType.MULTI_TARGET_UPDATE)
    } yield toUpdate

    db.run(dbAct.transactionally).flatMap { scheduled =>
      Future.traverse(scheduled){ device =>
        messageBusPublisher.publish(UpdateSpec(namespace, device, UpdateStatus.Pending))
      }.map(_ => scheduled)
    }
  }
}
