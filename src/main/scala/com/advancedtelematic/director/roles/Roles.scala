package com.advancedtelematic.director.roles

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdateAssignmentRepositorySupport, FileCacheRepositorySupport, FileCacheRequestRepositorySupport, MultiTargetUpdatesRepositorySupport, Errors => DBErrors}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.RoleType
import io.circe.Json
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._
import cats.syntax.option._
import scala.async.Async._

class Roles(rolesGeneration: RolesGeneration)
           (implicit val db: Database, val ec: ExecutionContext)
    extends DeviceUpdateAssignmentRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport {

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private def shouldBeRegenerated(ns: Namespace, device: DeviceId, version: Int): Future[Boolean] = async {
    val expired = await(fileCacheRepository.haveExpired(device, version))
    val wasUpdated = await(fileCacheRepository.roleWasUpdated(device, version, RoleType.TARGETS))
    expired || wasUpdated
  }

  private def updateCacheIfExpired(ns: Namespace, device: DeviceId, version: Int): Future[Int] =
    shouldBeRegenerated(ns, device, version).flatMap {
      case true =>
        val fcr = FileCacheRequest(ns, version + 1, device, FileCacheRequestStatus.PENDING, version + 1)
        rolesGeneration.processFileCacheRequest(fcr, assignmentsVersion = version.some).map(_ => version + 1)
      case false =>
        FastFuture.successful(version)
    }.recoverWith {
      case DBErrors.NoCacheEntry =>
        val fcr = FileCacheRequest(ns, version, device, FileCacheRequestStatus.PENDING, version)
        rolesGeneration.processFileCacheRequest(fcr).map(_ => version)
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
    assignmentVersion <- nextVersionToFetch(ns, device, currentVersion)
    latestDeviceVersion <- fileCacheRepository.fetchLatestVersion(device)
    latestVersion <- updateCacheIfExpired(ns, device, Math.max(assignmentVersion, latestDeviceVersion.getOrElse(assignmentVersion)))
  } yield latestVersion

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
