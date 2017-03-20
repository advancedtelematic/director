package com.advancedtelematic.director.db

import cats.syntax.show._
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceId, DeviceUpdateTarget, EcuSerial, FileCacheRequest, Image, UpdateId}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
import com.advancedtelematic.libats.data.Namespace
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._

object DeviceUpdate extends AdminRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRequestRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  import scala.util.control.NoStackTrace
  final case class DeviceUpdatedToWrongTarget(next_version: Int) extends Throwable(s"Device updated to the wrong target") with NoStackTrace

  private def checkTargets[T](namespace: Namespace, device: DeviceId, next_version: Int)
                          (withTargets:  Map[EcuSerial, CustomImage] => DBIO[T])
                          (implicit db: Database, ec: ExecutionContext): DBIO[Option[UpdateId]] = {
    adminRepository.fetchTargetVersionAction(namespace, device, next_version)
      .flatMap(withTargets)
      .andThen(adminRepository.fetchUpdateIdAction(namespace, device, next_version))
  }

  private def findNextVersionOrUpdate[T](device: DeviceId)(withVersion: Int => DBIO[Option[T]])
                                     (implicit db: Database, ec: ExecutionContext): DBIO[Option[(Int, T)]] = {
    deviceRepository.getNextVersionAction(device).asTry.flatMap {
      case Failure(Errors.MissingCurrentTarget) =>
        deviceRepository.updateDeviceVersionAction(device, 0).map(_ => None)
      case Failure(ex) => DBIO.failed(ex)
      case Success(next_version) => withVersion(next_version).map(x => x.map((next_version, _)))
    }
  }

  private def updateDeviceTargetAction(namespace: Namespace, device: DeviceId, next_version: Int, translatedManifest: Map[EcuSerial, Image])
                                      (implicit db: Database, ec: ExecutionContext): DBIO[Option[UpdateId]] = {
    checkTargets(namespace, device, next_version) { targets =>
      val translatedTargets = targets.mapValues(_.image)
      if (translatedTargets == translatedManifest) {
        deviceRepository.updateDeviceVersionAction(device, next_version)
      } else {
        _log.error(s"Device ${device.show} updated to the wrong target")
        _log.info {
          s"""version : $next_version
             |targets : $translatedTargets
             |manifest: $translatedManifest
           """.stripMargin
        }
        DBIO.failed(DeviceUpdatedToWrongTarget(next_version - 1)) // TODO, I rather not do - 1 here
      }
    }
  }

  def checkAgainstTarget(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest])
                        (implicit db: Database, ec: ExecutionContext): Future[Option[(Int, UpdateId)]] = {
    val translatedManifest = ecuImages.map(ecu => (ecu.ecu_serial, ecu.installed_image)).toMap

    val dbAct = setEcusAction(namespace, device, ecuImages){
      findNextVersionOrUpdate(device) { next_version =>
        updateDeviceTargetAction(namespace, device, next_version, translatedManifest)
      }
    }

    db.run(dbAct.transactionally)
  }

  protected [db] def clearTargetsFromAction(namespace: Namespace, device: DeviceId, version: Int)
                                           (implicit db: Database, ec: ExecutionContext): DBIO[Int] = {
    val dbAct = for {
      latestVersion <- adminRepository.getLatestVersion(namespace, device)
      nextTimestampVersion = latestVersion + 1
      fcr = FileCacheRequest(namespace, version, device, FileCacheRequestStatus.PENDING, nextTimestampVersion)
      _ <- deviceRepository.updateDeviceVersionAction(device, nextTimestampVersion)
      _ <- Schema.deviceTargets += DeviceUpdateTarget(device, None, nextTimestampVersion)
      _ <- fileCacheRequestRepository.persistAction(fcr)
    } yield (latestVersion)

    dbAct.transactionally
  }

  def clearTargetsFrom(namespace: Namespace, device: DeviceId, version: Int)
                      (implicit db: Database, ec: ExecutionContext): Future[Int] = db.run{
    clearTargetsFromAction(namespace, device, version)
  }

  def clearTargets(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest])
                  (implicit db: Database, ec: ExecutionContext): Future[Option[UpdateId]] = {
    val dbAct = for {
        _ <- setEcusAction(namespace, device, ecuImages)(DBIO.successful(None))
        current_version <- deviceRepository.getCurrentVersionAction(device)
        updateId <- adminRepository.fetchUpdateIdAction(namespace, device, current_version + 1)
        _ <- clearTargetsFromAction(namespace, device, current_version)
      } yield updateId

    db.run(dbAct.transactionally)
  }

  protected [db] def setEcusAction[T](namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest])(ifNotSame : DBIO[Option[T]])
                                  (implicit db: Database, ec: ExecutionContext): DBIO[Option[T]] = {
    val translatedManifest = ecuImages.map(ecu => (ecu.ecu_serial, ecu.installed_image)).toMap

    adminRepository.findImagesAction(namespace, device).flatMap { currentStored =>
      if (currentStored.toMap == translatedManifest) {
        DBIO.successful(None)
      } else {
        deviceRepository.persistAllAction(ecuImages).andThen(ifNotSame)
      }
    }
  }
}
