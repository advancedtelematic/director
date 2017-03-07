package com.advancedtelematic.director.db

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceId, EcuSerial, Image, Namespace, UpdateId}
import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
import com.advancedtelematic.director.http.{Errors => HttpErrors}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._

object DeviceUpdate extends AdminRepositorySupport
    with DeviceRepositorySupport {

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private def checkTargets[T](namespace: Namespace, device: DeviceId, next_version: Int)
                          (withTargets:  Map[EcuSerial, CustomImage] => DBIO[T])
                          (implicit db: Database, ec: ExecutionContext): DBIO[Option[UpdateId]] = {
    adminRepository.fetchTargetVersionAction(namespace, device, next_version)
      .flatMap(withTargets)
      .andThen(adminRepository.fetchUpdateIdAction(namespace, device, next_version))
  }

  private def findNextVersionOrUpdate[T](device: DeviceId)(withVersion: Int => DBIO[Option[T]])
                                     (implicit db: Database, ec: ExecutionContext): DBIO[Option[T]] = {
    deviceRepository.getNextVersionAction(device).asTry.flatMap {
      case Failure(Errors.MissingCurrentTarget) =>
        deviceRepository.updateDeviceVersionAction(device, 0).map (_ => None)
      case Failure(ex) => DBIO.failed(ex)
      case Success(next_version) => withVersion(next_version)
    }
  }

  private def updateDeviceTargetAction(namespace: Namespace, device: DeviceId, translatedManifest: Map[EcuSerial, Image])
                                      (implicit db: Database, ec: ExecutionContext): DBIO[Option[UpdateId]] = {
    findNextVersionOrUpdate(device) { next_version =>
      checkTargets(namespace, device, next_version) { targets =>
        if (targets.mapValues(_.image) == translatedManifest) {
          deviceRepository.updateDeviceVersionAction(device, next_version)
        } else {
          _log.info {
            s"""version : $next_version
               |targets : $targets
               |manifest: $translatedManifest
             """.stripMargin
          }
          _log.error(s"Device $device updated to the wrong target")
          DBIO.failed(HttpErrors.DeviceUpdatedToWrongTarget)
        }
      }
    }
  }

  def setEcus(coreClient: CoreClient)(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest])
             (implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    val translatedManifest = ecuImages.map(ecu => (ecu.ecu_serial, ecu.installed_image)).toMap

    val dbAct = db.run {
      adminRepository.findImagesAction(namespace, device).flatMap { currentStored =>
        if (currentStored.toMap == translatedManifest) {
          DBIO.successful(None)
        } else {
          deviceRepository.persistAllAction(ecuImages)
            .andThen(updateDeviceTargetAction(namespace, device, translatedManifest))
        }
      }.transactionally
    }
    dbAct.flatMap {
      case None => FastFuture.successful(Unit)
      case Some(updateId) => coreClient.updateReportOk(namespace, device, updateId)
    }
  }
}
