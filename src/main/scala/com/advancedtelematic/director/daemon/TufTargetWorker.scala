package com.advancedtelematic.director.daemon

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.{MultiTargetUpdate, MultiTargetUpdateRequest,
  TargetUpdate, TargetUpdateRequest}
import com.advancedtelematic.director.db.{AutoUpdateRepositorySupport, MultiTargetUpdatesRepositorySupport,
  SetMultiTargets}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.Messages.TufTargetAdded
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetName}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api.Database

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
        val toTarget = TargetUpdate(tufTargetAdded.filename, tufTargetAdded.checksum, tufTargetAdded.length)

        findAndSchedule(tufTargetAdded.namespace, custom.name, toTarget)
          .map(_ => Done)
    }
  }

  def findAndSchedule(namespace: Namespace, targetName: TargetName, toTarget: TargetUpdate): Future[Seq[UpdateId]] =
    autoUpdateRepository.findByTargetName(namespace, targetName).flatMap { devicesAndHw =>
      Future.traverse(createRequests(devicesAndHw, toTarget).toSeq) { case (request, devices) =>
        schedule(namespace, request, devices)
      }
    }

  def fromHwAndCurrentToRequest(hwAndCurrent: Seq[(HardwareIdentifier, TargetUpdate)], toTarget: TargetUpdate): MultiTargetUpdateRequest =
    MultiTargetUpdateRequest {
      hwAndCurrent.groupBy(_._1).mapValues(_.map(_._2).toSet).map {
        case (hw, fromTargets) =>
          val fromTarget = if (fromTargets.size == 1) Some(fromTargets.head) else None
          hw -> TargetUpdateRequest(fromTarget, toTarget)
      }.toMap
    }

  // share MultiTargetUpdateRequests with as many devices as possible
  def createRequests(devices: Map[DeviceId, Seq[(HardwareIdentifier, TargetUpdate)]],
                     toTarget: TargetUpdate): Map[MultiTargetUpdateRequest, Seq[DeviceId]] = {
    val mtus = devices.map { case (dev, img) =>
      dev -> fromHwAndCurrentToRequest(img, toTarget)
    }

    mtus.groupBy(_._2).map{ case (k, v) => k -> v.map(_._1).toSeq}
  }

  def schedule(namespace: Namespace, request: MultiTargetUpdateRequest,
               devices: Seq[DeviceId]): Future[UpdateId] = {
    val updateId = UpdateId.generate
    val m = MultiTargetUpdate(request, updateId, namespace)
    multiTargetUpdatesRepository.create(m).flatMap { _ =>
      setMultiTargets.setMultiUpdateTargetsForDevices(namespace, devices, updateId).map(_ => updateId)
    }
  }
}
