package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{Image, DeviceUpdateTarget}
import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

object DeviceUpdateResult {
  sealed abstract class DeviceUpdateResult

  final case object NoChange extends DeviceUpdateResult
  final case class UpdatedSuccessfully(updateTarget: DeviceUpdateTarget) extends DeviceUpdateResult
  // the Option on targets is if the device have targets or not
  final case class UpdatedToWrongTarget(timestamp: Int, targets: Option[Map[EcuSerial, Image]], manifest: Map[EcuSerial, Image]) extends DeviceUpdateResult

}

object DeviceUpdate extends AdminRepositorySupport
    with DeviceRepositorySupport
    with DeviceTargetRepositorySupport
    with EcuTargetRepositorySupport
    with FileCacheRequestRepositorySupport {
  import DeviceUpdateResult._

  def subMap[K, V](xs: Map[K,V], ys: Map[K,V]): Boolean = xs.forall {
    case (k,v) => ys.get(k).contains(v)
  }

  private def isEqualToUpdate(namespace: Namespace, device: DeviceId, next_version: Int, translatedManifest: Map[EcuSerial, Image])
                             (ifNot : Option[Map[EcuSerial, Image]] => DBIO[DeviceUpdateResult])
                             (implicit db: Database, ec: ExecutionContext): DBIO[DeviceUpdateResult] =
    deviceTargetRepository.updateExistsAction(namespace, device, next_version).flatMap {
      case true => ecuTargetRepository.fetchTargetVersionAction(namespace, device, next_version).flatMap { targets =>
        val translatedTargets = targets.mapValues(_.image)
        if (subMap(translatedTargets, translatedManifest)) {
          for {
            _ <- deviceRepository.updateDeviceVersionAction(device, next_version)
            deviceUpdateTarget <- deviceTargetRepository.fetchDeviceUpdateTargetAction(namespace, device, next_version)
          } yield UpdatedSuccessfully(deviceUpdateTarget)
        } else {
          ifNot(Some(translatedTargets))
        }
      }
      case false => ifNot(None)
    }

  def checkAgainstTarget(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest])
                        (implicit db: Database, ec: ExecutionContext): Future[DeviceUpdateResult] = {
    val translatedManifest = ecuImages.map(ecu => (ecu.ecu_serial, ecu.installed_image)).toMap

    val dbAct = deviceRepository.getCurrentVersionAction(device).flatMap {
      case None => isEqualToUpdate(namespace, device, 1, translatedManifest) { _ =>
        deviceRepository.updateDeviceVersionAction(device, 0).map(_ => NoChange)
      }
      case Some(current_version) =>
        val next_version = current_version + 1
        isEqualToUpdate(namespace, device, next_version, translatedManifest) { translatedTargets =>
          adminRepository.findImagesAction(namespace, device).map { currentStored =>
            if (currentStored.toMap == translatedManifest) {
              NoChange
            } else {
              UpdatedToWrongTarget(current_version, translatedTargets, translatedManifest)
            }
          }
        }
    }.flatMap(x => deviceRepository.persistAllAction(namespace, ecuImages).map(_ => x))

    db.run(dbAct.transactionally)
  }

  private def copyTargetsAction(namespace: Namespace, device: DeviceId, failedTargets: Map[EcuSerial, TargetFilename],
                                deviceVersion: Int, nextTimestampVersion: Int)(implicit db: Database, ec: ExecutionContext) =
    if (failedTargets.isEmpty) {
      ecuTargetRepository.copyTargetsAction(namespace, device, deviceVersion, nextTimestampVersion)
    } else {
      ecuTargetRepository.copyTargetsAction(namespace, device, nextTimestampVersion - 1, nextTimestampVersion, failedTargets)
    }

  private [db] def clearTargetsFromAction(namespace: Namespace, device: DeviceId, deviceVersion: Int, failedTargets: Map[EcuSerial, TargetFilename])
                                         (implicit db: Database, ec: ExecutionContext): DBIO[Int] = {
    val dbAct = for {
      latestScheduledVersion <- deviceTargetRepository.getLatestScheduledVersion(namespace, device)
      nextTimestampVersion = latestScheduledVersion + 1
      _ <- copyTargetsAction(namespace, device, failedTargets, deviceVersion, nextTimestampVersion)
      _ <- deviceTargetRepository.updateDeviceTargetsAction(device, None, None, nextTimestampVersion)
      _ <- deviceRepository.updateDeviceVersionAction(device, nextTimestampVersion)
    } yield latestScheduledVersion

    dbAct.transactionally
  }

  def clearTargetsFrom(namespace: Namespace, device: DeviceId, deviceVersion: Int, failedTargets: Map[EcuSerial, TargetFilename])
                      (implicit db: Database, ec: ExecutionContext): Future[Int] = db.run {
    clearTargetsFromAction(namespace, device, deviceVersion, failedTargets)
  }
}
