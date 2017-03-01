package com.advancedtelematic.director.db

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.DataType.{DeviceId, Namespace, UpdateId}
import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
import com.advancedtelematic.director.http.{Errors => HttpErrors}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._

object DeviceUpdate extends AdminRepositorySupport
    with DeviceRepositorySupport {

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private def updateDeviceTargetAction(namespace: Namespace, device: DeviceId, ecuManifests: Seq[EcuManifest])
                                      (implicit db: Database, ec: ExecutionContext): DBIO[Option[UpdateId]] = {

    val translatedManifest = ecuManifests.groupBy(_.ecu_serial).mapValues(_.head.installed_image)

    val dbAct = deviceRepository.getNextVersionAction(device).flatMap { next_version =>
      adminRepository.fetchTargetVersionAction(namespace, device, next_version).flatMap { targets =>
        if (targets.mapValues(_.image) == translatedManifest) {
          deviceRepository.updateDeviceVersionAction(device, next_version)
            .andThen(adminRepository.fetchUpdateIdAction(namespace, device, next_version))
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
    }.asTry.flatMap {
      case Failure(Errors.MissingCurrentTarget) =>
        deviceRepository.updateDeviceVersionAction(device, 0).map (_ => None)
      case Failure(ex) => DBIO.failed(ex)
      case Success(x) => DBIO.successful(x)
    }

    dbAct
  }

  def setEcus(coreClient: CoreClient)(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest])
             (implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    val dbAct = db.run {
      deviceRepository.persistAllAction(ecuImages)
        .andThen(updateDeviceTargetAction(namespace, device, ecuImages))
        .transactionally
    }
    dbAct.flatMap {
      case None => FastFuture.successful(Unit)
      case Some(updateId) => coreClient.updateReportOk(namespace, device, updateId)
    }
  }
}
