package com.advancedtelematic.director.daemon

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.{DeviceId, FileCacheRequest, FileInfo, Image, Namespace}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRequestRepositorySupport}
import com.advancedtelematic.libtuf.data.TufDataType.HashMethod
import eu.timepit.refined.api.Refined
import org.genivi.sota.messaging.Messages.CampaignLaunched

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

object TreehubCampaignWorker extends AdminRepositorySupport with FileCacheRequestRepositorySupport {

  def action(cl: CampaignLaunched)(implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    if(cl.pkg.name.get.startsWith("treehub-")) {
      async {
        val deviceIds = cl.devices.map(deviceId => DeviceId(deviceId.toJava))
        val image = getImage(cl)
        await(Future.sequence(deviceIds.map { deviceId =>
          db.run(adminRepository.getPrimaryEcuForDevice(deviceId))
        }))
        .zip(deviceIds)
        .map { elem =>
          setTargets(Namespace(cl.namespace.get), elem._2, SetTarget(Map(elem._1 -> image)))
        }
      }
    } else {
      FastFuture.successful(())
    }
  }

  private def getImage(cl: CampaignLaunched): Image =
    Image(cl.pkgUri.path.toString(),
      FileInfo(Map(HashMethod.SHA256 -> Refined.unsafeApply(cl.pkgChecksum)), cl.pkgSize.toInt))

  def setTargets(namespace: Namespace, device: DeviceId, targets: SetTarget)
                        (implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    async {
      val new_version = await(adminRepository.updateTarget(namespace, device, targets.updates))
      //TODO: check what this method actually does
      await(fileCacheRequestRepository.persist(FileCacheRequest(namespace, new_version, device,
                                                                FileCacheRequestStatus.PENDING)))
    }
  }

}
