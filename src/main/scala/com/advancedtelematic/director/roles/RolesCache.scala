package com.advancedtelematic.director.roles

import com.advancedtelematic.director.data.DataType.{DeviceId, FileCacheRequest}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheRepositorySupport, FileCacheRequestRepositorySupport}
import com.advancedtelematic.libats.data.Namespace
import io.circe.Json
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

class RolesCache(rolesGeneration: RolesGeneration)
                (implicit db: Database, ec: ExecutionContext) extends DeviceRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport {

  private def checkCache(ns: Namespace, device: DeviceId): Future[Int] = async {
    val version = await(deviceRepository.getNextVersion(device))

    if (await(fileCacheRepository.haveExpired(device, version))) {
      val targetVersion = await(fileCacheRequestRepository.findTargetVersion(ns, device, version))
      val fcr = FileCacheRequest(ns, targetVersion, device, None, FileCacheRequestStatus.PENDING, version)
      await(rolesGeneration.generateFiles(fcr))
    }

    version
  }

  def fetchTimestamp(ns: Namespace, device: DeviceId): Future[Json] =
    checkCache(ns, device).flatMap{ version =>
      fileCacheRepository.fetchTimestamp(device, version)
    }

  def fetchTargets(ns: Namespace, device: DeviceId): Future[Json] =
    checkCache(ns, device).flatMap{ version =>
      fileCacheRepository.fetchTarget(device, version)
    }

  def fetchSnapshot(ns: Namespace, device: DeviceId): Future[Json] =
    checkCache(ns, device).flatMap{ version =>
      fileCacheRepository.fetchSnapshot(device, version)
    }
}
