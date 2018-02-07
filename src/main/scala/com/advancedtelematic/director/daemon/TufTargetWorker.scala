package com.advancedtelematic.director.daemon

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.{MultiTargetUpdateRequest, TargetUpdate, TargetUpdateRequest}
import com.advancedtelematic.director.db.{AutoUpdateRepositorySupport, MultiTargetUpdatesRepositorySupport,
  SetMultiTargets}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId, ValidTargetFilename}
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetName}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import com.advancedtelematic.libtuf_server.data.Messages.TufTargetAdded
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api.Database
import scala.util.{Failure, Success}

class TufTargetWorker(setMultiTargets: SetMultiTargets)(implicit db: Database, ec: ExecutionContext)
    extends AutoUpdateRepositorySupport
    with MultiTargetUpdatesRepositorySupport
{
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def action(tufTargetAdded: TufTargetAdded): Future[Done] = {
    tufTargetAdded.custom match {
      case None =>
        _log.info("TufTargetAdded doesn't have a custom field, ignore")
        FastFuture.successful(Done)
      case Some(custom) =>
        custom.targetFormat match {
          case None =>
            _log.info(s"TufTargetAdded dosen't have a targetFormat, ignore")
            FastFuture.successful(Done)
          case(Some(targetFormat)) =>
            tufTargetAdded.filename.value.refineTry[ValidTargetFilename] match {
              case Failure(_) =>
                _log.error(s"Could not parse filename from $tufTargetAdded")
                FastFuture.successful(Done)
              case Success(filename) =>
                val toTarget = TargetUpdate(filename, tufTargetAdded.checksum, tufTargetAdded.length)
                findAndSchedule(tufTargetAdded.namespace, custom.name, targetFormat, toTarget)
                  .map(_ => Done)
            }
        }
    }
  }

  def findAndSchedule(namespace: Namespace, targetName: TargetName, targetFormat: TargetFormat, toTarget: TargetUpdate): Future[Seq[UpdateId]] =
    autoUpdateRepository.findByTargetName(namespace, targetName).flatMap { devicesAndHw =>
      Future.traverse(createRequests(devicesAndHw, targetFormat, toTarget).toSeq) { case (request, devices) =>
        schedule(namespace, request, devices)
      }
    }

  def fromHwAndCurrentToRequest(hwAndCurrent: Seq[(HardwareIdentifier, TargetUpdate)],
                                targetFormat: TargetFormat, toTarget: TargetUpdate): MultiTargetUpdateRequest =
    MultiTargetUpdateRequest {
      hwAndCurrent.groupBy(_._1).mapValues(_.map(_._2).toSet).map {
        case (hw, fromTargets) =>
          val fromTarget = if (fromTargets.size == 1) Some(fromTargets.head) else None
          hw -> TargetUpdateRequest(fromTarget, toTarget, targetFormat, false)
      }.toMap
    }

  // share MultiTargetUpdateRequests with as many devices as possible
  def createRequests(devices: Map[DeviceId, Seq[(HardwareIdentifier, TargetUpdate)]],
                     targetFormat: TargetFormat, toTarget: TargetUpdate): Map[MultiTargetUpdateRequest, Seq[DeviceId]] = {
    val mtus = devices.map { case (dev, img) =>
      dev -> fromHwAndCurrentToRequest(img, targetFormat, toTarget)
    }

    mtus.groupBy(_._2).map{ case (k, v) => k -> v.map(_._1).toSeq}
  }

  def schedule(namespace: Namespace, request: MultiTargetUpdateRequest,
               devices: Seq[DeviceId]): Future[UpdateId] = {
    val updateId = UpdateId.generate
    val m = request.multiTargetUpdateRows(updateId, namespace)
    multiTargetUpdatesRepository.create(m).flatMap { _ =>
      setMultiTargets.setMultiUpdateTargetsForDevices(namespace, devices, updateId).map(_ => updateId)
    }
  }
}
