package com.advancedtelematic.director.roles

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DataType.{DeviceUpdateAssignment, FileCacheRequest}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdateAssignmentRepositorySupport, FileCacheRepositorySupport, FileCacheRequestRepositorySupport, MultiTargetUpdatesRepositorySupport, Errors => DBErrors}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.RoleType
import io.circe.Json
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._

class Roles(rolesGeneration: RolesGeneration)
           (implicit val db: Database, val ec: ExecutionContext)
    extends DeviceUpdateAssignmentRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport
    with MultiTargetUpdatesRepositorySupport {

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  /*
  Due to https://saeljira.it.here.com/browse/OTA-4539 some devices downloaded targets.json without a correlationId,
  so we need to check if they need a new targets.json with the same targets but with correlation id added.

  This method gets the latest targets.json from the database and checks whether it should have a correlationId but doesn't.
  This will trigger a new targets.json generation if the correlation is missing
   */
  private def correlationIdMissing(json: Json, correlationId: Option[CorrelationId]): Boolean = {
    val targetsCorrelationMissing = json.hcursor.downField("signed").downField("custom").downField("correlationId").failed
    _log.debug(s"correlationId=$correlationId,correlationIdMissing=$targetsCorrelationMissing")
    correlationId.isDefined && targetsCorrelationMissing
  }

  private def shouldBeRegenerated(ns: Namespace, device: DeviceId, version: Int, correlationId: Option[CorrelationId]): Future[Boolean] = async {
    val isExpired = await(fileCacheRepository.haveExpired(device, version))
    val wasUpdated = await(fileCacheRepository.roleWasUpdated(device, version, RoleType.TARGETS))
    val lastTargets = await(fileCacheRepository.fetchTarget(device, version))
    isExpired || wasUpdated || correlationIdMissing(lastTargets, correlationId)
  }

  private def updateCacheIfExpired(ns: Namespace, device: DeviceId, latestTargetsVersion: Int, assignment: Option[DeviceUpdateAssignment]): Future[Int] = async {
    val correlationId = assignment.flatMap(_.correlationId)
    val regenerate = await(shouldBeRegenerated(ns, device, latestTargetsVersion, correlationId))

    if(regenerate) await {
      val nextVersion = latestTargetsVersion + 1
      val fcr = FileCacheRequest(ns, nextVersion, device, FileCacheRequestStatus.PENDING, nextVersion, correlationId = correlationId)
      rolesGeneration.processFileCacheRequest(fcr, assignmentsVersion = assignment.map(_.version)).map(_ => nextVersion)
    } else
      latestTargetsVersion
  }.recoverWith {
    case DBErrors.NoCacheEntry =>
      val fcr = FileCacheRequest(ns, latestTargetsVersion, device, FileCacheRequestStatus.PENDING, latestTargetsVersion, assignment.flatMap(_.correlationId))
      rolesGeneration.processFileCacheRequest(fcr, assignmentsVersion = assignment.map(_.version)).map(_ => latestTargetsVersion)
  }

  private def nextVersionToFetch(ns: Namespace, device: DeviceId, currentVersion: Int): Future[Int] = {
    val timestampVersion = currentVersion + 1
    deviceUpdateAssignmentRepository.find(ns, device, timestampVersion).flatMap {
      case Some(_)  => fileCacheRepository.versionIsCached(device, timestampVersion).flatMap {
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
      case None => FastFuture.successful(currentVersion)
    }
  }

  private def findVersion(ns: Namespace, device: DeviceId): Future[Int] = for {
    currentVersion <- deviceRepository.getCurrentVersion(device)
    assignmentVersion <- nextVersionToFetch(ns, device, currentVersion)
    latestDeviceVersion <- fileCacheRepository.fetchLatestVersion(device)
    assignment <- deviceUpdateAssignmentRepository.find(ns, device, assignmentVersion)
    latestVersion <- updateCacheIfExpired(ns, device, Math.max(assignmentVersion, latestDeviceVersion.getOrElse(assignmentVersion)), assignment)
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
