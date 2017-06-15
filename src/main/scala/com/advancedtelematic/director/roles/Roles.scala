package com.advancedtelematic.director.roles

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport,
  FileCacheRepositorySupport, FileCacheRequestRepositorySupport, MultiTargetUpdatesRepositorySupport}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import io.circe.Json
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

class Roles(rolesGeneration: RolesGeneration)
           (implicit db: Database, ec: ExecutionContext) extends AdminRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport {

  private val log = LoggerFactory.getLogger(this.getClass)

  private def updateCacheIfExpired(ns: Namespace, device: DeviceId, version: Int): Future[Done] =
    fileCacheRepository.haveExpired(device,version).flatMap {
      case true => fileCacheRequestRepository.findTargetVersion(ns, device, version).flatMap {targetVersion =>
        val fcr = FileCacheRequest(ns, targetVersion, device, None, FileCacheRequestStatus.PENDING, version)
        rolesGeneration.processFileCacheRequest(fcr).map(_ => Done)
      }
      case false => FastFuture.successful(Done)
  }

  private def nextVersionToFetch(ns: Namespace, device: DeviceId, currentVersion: Int): Future[Int] = {
    val timestampVersion = currentVersion + 1
    adminRepository.updateExists(ns, device, timestampVersion).flatMap {
      case true  => fileCacheRepository.versionIsCached(device, timestampVersion).flatMap {
        case true => FastFuture.successful(timestampVersion)
        case false =>
          fileCacheRequestRepository.findTargetVersion(ns, device, timestampVersion).flatMap { targetVersion =>
            rolesGeneration.tryToGenerate(ns, device, targetVersion, timestampVersion).map(_ => timestampVersion).recover {
              case MtuDiffDataMissing => currentVersion
            }
          }
      }
      case false => FastFuture.successful(currentVersion)
    }
  }

  private def findVersion(ns: Namespace, device: DeviceId): Future[Int] = for {
    currentVersion <- deviceRepository.getCurrentVersion(device)
    nextVersion <- nextVersionToFetch(ns, device, currentVersion)
    _ <- updateCacheIfExpired(ns, device, nextVersion)
  } yield nextVersion

  def fetchTimestamp(ns: Namespace, device: DeviceId): Future[Json] =
    findVersion(ns, device).flatMap{ version =>
      fileCacheRepository.fetchTimestamp(device, version)
    }

  def fetchTargets(ns: Namespace, device: DeviceId): Future[Json] =
    findVersion(ns, device).flatMap{ version =>
      fileCacheRepository.fetchTarget(device, version)
    }

  def fetchSnapshot(ns: Namespace, device: DeviceId): Future[Json] =
    findVersion(ns, device).flatMap{ version =>
      fileCacheRepository.fetchSnapshot(device, version)
    }
}
