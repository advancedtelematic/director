package com.advancedtelematic.director.daemon

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.AdminRequest.SetTarget
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceId, FileInfo, Namespace, UpdateId}
import com.advancedtelematic.director.db.{AdminRepositorySupport, Errors => DBErrors, SetTargets}
import com.advancedtelematic.libtuf.data.TufDataType.HashMethod
import eu.timepit.refined.api.Refined
import org.genivi.sota.messaging.Messages.CampaignLaunched
import org.slf4j.LoggerFactory
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

object CampaignWorker extends AdminRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def action(cl: CampaignLaunched)(implicit db: Database, ec: ExecutionContext): Future[Done] = {

    val action = async {
      _log.info(s"received event CampaignLaunched ${cl.updateId}")
      val deviceIds = cl.devices.map(deviceId => DeviceId(deviceId.toJava)).toSeq
      val image = getImage(cl)

      val primEcus = await(Future.sequence(deviceIds.map(adminRepository.getPrimaryEcuForDevice)))

      val devTargets = deviceIds.zip(primEcus.map(prim => SetTarget(Map(prim -> image))))
      await(SetTargets.setTargets(Namespace(cl.namespace.get), devTargets, Some(UpdateId(cl.updateId.toJava))))

      Done
    }

    action.recoverWith {
      case DBErrors.DeviceMissingPrimaryEcu =>
        _log.info(s"Ignoring campaign for ${cl.updateId} since the device is not registered.")
        FastFuture.successful(Done)
    }
  }

  private def getImage(cl: CampaignLaunched): CustomImage =
    CustomImage(cl.pkg.mkString,
                FileInfo(Map(HashMethod.SHA256 -> Refined.unsafeApply(cl.pkgChecksum)), cl.pkgSize.toInt),
                cl.pkgUri)
}
