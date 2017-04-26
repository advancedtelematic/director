package com.advancedtelematic.director.db

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.DataType.{CustomImage, DeviceId, LaunchedMultiTargetUpdate, UpdateId}
import com.advancedtelematic.director.data.LaunchedMultiTargetUpdateStatus
import com.advancedtelematic.director.data.UpdateType
import com.advancedtelematic.libats.data.Namespace
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

object SetMultiTargets extends AdminRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport
    with LaunchedMultiTargetUpdateRepositorySupport
    with UpdateTypesRepositorySupport {

  //this composition should probably be somewhere else
  implicit class MapOps[A,B](map: Map[A, B]) {
    def composeMap[C](map2: Map[B, C]): Map[A, C] =
      map.mapValues(map2.get(_)).collect { case (k, Some(v)) => k -> v}
  }

  def setMultiUpdateTargets(namespace: Namespace, device: DeviceId, updateId: UpdateId)
                           (implicit db: Database, ec: ExecutionContext): Future[Unit] = {
    val dbAct = for {
      hwRows <- multiTargetUpdatesRepository.fetchAction(updateId, namespace)
      hwTargets = hwRows.map(mtu => mtu.hardwareId -> CustomImage(mtu.image, Uri())).toMap
      ecus <- adminRepository.fetchHwMappingAction(namespace, device)
      targets = ecus.composeMap(hwTargets)
      new_version <- SetTargets.deviceAction(namespace, device, Some(updateId), targets)
      _ <- launchedMultiTargetUpdateRepository.persistAction(LaunchedMultiTargetUpdate(device, updateId, new_version, LaunchedMultiTargetUpdateStatus.Pending))
      _ <- updateTypesRepository.persistAction(updateId, UpdateType.MULTI_TARGET_UPDATE)
    } yield ()

    db.run(dbAct.transactionally)
  }
}
