package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{DeviceUpdateAssignment, Image}
import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, DeviceUpdateAssignmentRepositorySupport, EcuUpdateAssignmentRepositorySupport, FileCacheRepositorySupport, FileCacheRequestRepositorySupport}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

object DeviceUpdateResult {
  sealed abstract class DeviceUpdateResult

  final case object NoUpdate extends DeviceUpdateResult
  final case class UpdateNotCompleted(assignment: DeviceUpdateAssignment) extends DeviceUpdateResult
  final case class UpdateSuccessful(assignment: DeviceUpdateAssignment) extends DeviceUpdateResult
  final case class UpdateUnexpectedTarget(
    assignment: DeviceUpdateAssignment,
    expectedTargets: Map[EcuIdentifier, Image],
    actualTargets: Map[EcuIdentifier, Image]
  ) extends DeviceUpdateResult

}

object DeviceUpdate extends AdminRepositorySupport
    with DeviceRepositorySupport
    with DeviceUpdateAssignmentRepositorySupport
    with EcuUpdateAssignmentRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport {
  import DeviceUpdateResult._

  private def subMap[K, V](xs: Map[K,V], ys: Map[K,V]): Boolean = xs.forall {
    case (k,v) => ys.get(k).contains(v)
  }

  def checkAgainstTarget(namespace: Namespace, device: DeviceId, ecuManifests: Seq[EcuManifest])
                        (implicit db: Database, ec: ExecutionContext): Future[DeviceUpdateResult] = {
    val dbAction = for {
      currentVersion          <- deviceRepository.getCurrentVersionSetIfInitialAction(device)
      latestAssignmentVersion <- deviceUpdateAssignmentRepository.fetchLatestNoLock(namespace, device)
      updateResult            <- if( latestAssignmentVersion > currentVersion)
                                   handleUpdate(namespace, device, ecuManifests, latestAssignmentVersion)
                                 else DBIO.successful(NoUpdate)
      _                       <- deviceRepository.persistAllAction(namespace, ecuManifests)
    } yield updateResult

    db.run(dbAction.transactionally)
  }

  private def handleUpdate(namespace: Namespace, device: DeviceId, ecuManifests: Seq[EcuManifest], nextVersion: Int)
    (implicit db: Database, ec: ExecutionContext): DBIO[DeviceUpdateResult] = {

    deviceUpdateAssignmentRepository.fetchAction(namespace, device, nextVersion).flatMap { deviceUpdateTarget =>
      ecuUpdateAssignmentRepository.fetchAction(namespace, device, nextVersion).flatMap { ecuTargets =>
        fileCacheRepository.fetchLatestVersionAction(device).flatMap { latestDeviceVersion =>
          val actualTargets = ecuManifests.map(ecu => (ecu.ecu_serial, ecu.installed_image)).toMap
          val expectedTargets = ecuTargets.mapValues(_.image)
          if (subMap(expectedTargets, actualTargets)) {
            deviceRepository.updateDeviceVersionAction(device, latestDeviceVersion.getOrElse(nextVersion)).map { _ =>
              UpdateSuccessful(deviceUpdateTarget)
            }
          } else {
            adminRepository.findImagesAction(namespace, device).map { currentStored =>
              if (currentStored.toMap == actualTargets) {
                UpdateNotCompleted(deviceUpdateTarget)
              } else {
                UpdateUnexpectedTarget(deviceUpdateTarget, expectedTargets, actualTargets)
              }
            }
          }
        }
      }
    }
  }

  private [db] def clearTargetsFromAction(namespace: Namespace, device: DeviceId, deviceVersion: Int)
                                         (implicit db: Database, ec: ExecutionContext): DBIO[Int] = {
    val dbAct = for {
      latestScheduledVersion <- deviceUpdateAssignmentRepository.fetchLatestAction(namespace, device)
      currentTimestampVersion <- fileCacheRepository.fetchLatestVersionAction(device)
      nextTimestampVersion = currentTimestampVersion.getOrElse(latestScheduledVersion) + 1
      _ <- deviceUpdateAssignmentRepository.persistAction(namespace, device, None, None, nextTimestampVersion)
      _ <- deviceRepository.updateDeviceVersionAction(device, nextTimestampVersion)
    } yield latestScheduledVersion

    dbAct.transactionally
  }

  def clearTargetsFrom(namespace: Namespace, device: DeviceId, deviceVersion: Int)
                      (implicit db: Database, ec: ExecutionContext): Future[Int] = db.run {
    clearTargetsFromAction(namespace, device, deviceVersion)
  }
}
