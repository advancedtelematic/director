package com.advancedtelematic.director.manifest

import cats.syntax.either._
import cats.syntax.show._
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{DeviceId, Namespace, UpdateId}
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, DeviceManifest, EcuManifest, OperationResult}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate}
import com.advancedtelematic.director.http.{Errors => HttpErrors}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import org.slf4j.LoggerFactory
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(coreClient: CoreClient,
                           verifier: ClientKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
  extends DeviceRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[DeviceManifest]): Future[Unit] = async {
    val ecus = await(deviceRepository.findEcus(namespace, device))
    val ecuImages = await(Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan)))

    deviceManifestOperationResults(signedDevMan.signed) match {
      case Nil =>
        val okReport = Seq(OperationResult("", 0, "Device did not report an operationresult, but is at the correct target"))
        await(clientReportedNoErrors(namespace, device, ecuImages, okReport))
      case operations =>
        if (operations.forall(_.isSuccess)) {
          await(clientReportedNoErrors(namespace, device, ecuImages, operations))
        } else {
          _log.info(s"Device ${device.show} reports errors during install: $operations")
          val mUpdateId = await(DeviceUpdate.clearTargets(namespace, device, ecuImages))
          await(maybeUpdateCore(namespace, device, operations, mUpdateId))
        }
    }
  }

  private def clientReportedNoErrors(namespace: Namespace, device: DeviceId, ecuImages: Seq[EcuManifest],
                                     operations: Seq[OperationResult]): Future[Unit] =
    DeviceUpdate.checkAgainstTarget(namespace, device, ecuImages).map((_, operations)).recoverWith {
      case HttpErrors.DeviceUpdatedToWrongTarget =>
        DeviceUpdate.clearTargets(namespace,device, ecuImages).map { update =>
          val errorReport = Seq(OperationResult("",4, "director and device not in sync"))
          (update, errorReport)
        }
    }.flatMap { case (update, ops) =>
      maybeUpdateCore(namespace, device, ops, update)
    }

  private def maybeUpdateCore(namespace: Namespace, device: DeviceId, operations: Seq[OperationResult],
                              mUpdateId: Option[UpdateId]): Future[Unit] = async {
    mUpdateId match {
      case None => ()
      case Some(updateId) =>
        await(coreClient.updateReport(namespace, device, updateId, operations))
    }
  }

  private def deviceManifestOperationResults(deviceManifest: DeviceManifest): Seq[OperationResult] = {
    deviceManifest.ecu_version_manifest.flatMap(_.signed.custom.flatMap(_.as[CustomManifest].toOption))
      .map(_.operation_result)
  }
}
