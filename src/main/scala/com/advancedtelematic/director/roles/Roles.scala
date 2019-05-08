package com.advancedtelematic.director.roles

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdateAssignmentRepositorySupport, Errors => DBErrors,
  FileCacheRepositorySupport, FileCacheRequestRepositorySupport, MultiTargetUpdatesRepositorySupport}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import io.circe.Json
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

class Roles(rolesGeneration: RolesGeneration)
           (implicit val db: Database, val ec: ExecutionContext)
    extends DeviceUpdateAssignmentRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport {

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private def updateCacheIfExpired(ns: Namespace, device: DeviceId, version: Int): Future[Done] =
    fileCacheRepository.haveExpired(device,version).flatMap {
      case true =>
        val fcr = FileCacheRequest(ns, version, device, FileCacheRequestStatus.PENDING, version)
        rolesGeneration.processFileCacheRequest(fcr).map(_ => Done)
      case false =>
        FastFuture.successful(Done)
    }.recoverWith {
      case DBErrors.NoCacheEntry =>
        val fcr = FileCacheRequest(ns, version, device, FileCacheRequestStatus.PENDING, version)
        rolesGeneration.processFileCacheRequest(fcr).map(_ => Done)
    }

  private def nextVersionToFetch(ns: Namespace, device: DeviceId, currentVersion: Int): Future[Int] = {
    val timestampVersion = currentVersion + 1
    deviceUpdateAssignmentRepository.exists(ns, device, timestampVersion).flatMap {
      case true  => fileCacheRepository.versionIsCached(device, timestampVersion).flatMap {
        case true => FastFuture.successful(timestampVersion)
        case false =>
          fileCacheRequestRepository.findByVersion(ns, device, timestampVersion).flatMap { fcr =>
            rolesGeneration.processFileCacheRequest(fcr).map(_ => timestampVersion).recover {
              case MtuDiffDataMissing =>
                _log.info(s"MtuDiffDataMissing missing for $device currently at version $currentVersion")
                currentVersion
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

  def fetchTargets(ns: Namespace, device: DeviceId): Future[Json] = {
    def act(): Future[Json] = findVersion(ns, device).flatMap{ version =>
        deviceRepository.setAsInFlight(ns, device, version).flatMap { _ =>
          fileCacheRepository.fetchTarget(device, version)
        }
    }
    act().recoverWith {
      case DBErrors.FetchingCancelledUpdate => act()
    }
  }

  def fetchSnapshot(ns: Namespace, device: DeviceId): Future[Json] =
    findVersion(ns, device).flatMap{ version =>
      fileCacheRepository.fetchSnapshot(device, version)
    }
}
