package com.advancedtelematic.director.repo

import com.advancedtelematic.director.db.{AssignmentsRepositorySupport, DbSignedRoleRepositorySupport, EcuTargetsRepositorySupport}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.ClientDataType.{TargetsRole, TufRole}
import com.advancedtelematic.libtuf.data.TufDataType.{JsonSignedPayload, RepoId}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.libtuf_server.repo.server._
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceRoleGeneration(keyserverClient: KeyserverClient)(implicit val db: Database, val ec: ExecutionContext)
  extends AssignmentsRepositorySupport with DbSignedRoleRepositorySupport with EcuTargetsRepositorySupport {

  import scala.async.Async._

  private val _log = LoggerFactory.getLogger(this.getClass)

  private val roleGeneration = (ns: Namespace, device: DeviceId) => {
    val itemsProvider = new DeviceTargetProvider(ns, device)
    val signedRoleProvider = new DeviceSignedRoleProvider(ns, device)
    new SignedRoleGeneration(keyserverClient, itemsProvider, signedRoleProvider)
  }

  private val roleRefresher =  (ns: Namespace, device: DeviceId) => {
    val itemsProvider = new DeviceTargetProvider(ns, device)
    val signedRoleProvider = new DeviceSignedRoleProvider(ns, device)
    new RepoRoleRefresh(keyserverClient, signedRoleProvider, itemsProvider)
  }

  private def targetsIsOutdated(deviceId: DeviceId): Future[Boolean] = async {
    val lastTargetsAt = await(dbSignedRoleRepository.findLastCreated[TargetsRole](deviceId))
    val lastAssignmentAt = await(assignmentsRepository.findLastCreated(deviceId))

    lastTargetsAt
      .zip(lastAssignmentAt)
      .exists { case (lastTargetsTs, lastAssignmentTs) => lastAssignmentTs.isAfter(lastTargetsTs) }
  }

  def findFreshTargets(ns: Namespace, repoId: RepoId, deviceId: DeviceId): Future[JsonSignedPayload] = async {
    val isOutdated = await(targetsIsOutdated(deviceId))

    if(isOutdated) {
      _log.info(s"targets for $deviceId is outdated, generating fresh using current assignments")
      val t = await(roleGeneration(ns, deviceId).regenerateAllSignedRoles(repoId))
      await(assignmentsRepository.setAllInFlight(deviceId))
      t
    } else { // return existing/refreshed targets
      implicit val refresher = roleRefresher(ns, deviceId)
      val targets = await(roleGeneration(ns, deviceId).findRole[TargetsRole](repoId))
      targets.content
    }
  }

  def findFreshDeviceRole[T : TufRole](ns: Namespace, repoId: RepoId, deviceId: DeviceId): Future[JsonSignedPayload] = {
    implicit val refresher = roleRefresher(ns, deviceId)
    roleGeneration(ns, deviceId).findRole[T](repoId).map(_.content)
  }
}
