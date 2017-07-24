package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directive1
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, DeviceRegistration, LegacyDeviceManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.director.manifest.{AfterDeviceManifestUpdate, DeviceManifestUpdate}
import com.advancedtelematic.director.roles.Roles
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RoleType, SignedPayload, TufKey}
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import slick.jdbc.MySQLProfile.api._

class DeviceResource(extractNamespace: Directive1[Namespace],
                     verifier: TufKey => Verifier,
                     coreClient: CoreClient,
                     keyserverClient: KeyserverClient,
                     roles: Roles)
                    (implicit db: Database, ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
    extends DeviceRepositorySupport
    with FileCacheRepositorySupport
    with RepoNameRepositorySupport {
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.Route

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  private val afterUpdate = new AfterDeviceManifestUpdate(coreClient)
  private val deviceManifestUpdate = new DeviceManifestUpdate(afterUpdate, verifier)

  def fetchRoot(namespace: Namespace): Route = {
    val f = repoNameRepository.getRepo(namespace).flatMap { repo =>
      keyserverClient.fetchRootRole(repo)
    }
    complete(f)
  }

  def registerDevice(ns: Namespace, device: DeviceId, regDev: DeviceRegistration): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete(Errors.PrimaryIsNotListedForDevice)
      case Some(_) => complete(deviceRepository.create(ns, device, primEcu, regDev.ecus))
    }
  }

  val route = extractNamespace { ns =>
    pathPrefix("device" / DeviceId.Path) { device =>
      post {
        (path("ecus") & entity(as[DeviceRegistration])) { regDev =>
          registerDevice(ns, device, regDev)
        }
      } ~
      put {
        path("manifest") {
          entity(as[SignedPayload[DeviceManifest]]) { devMan =>
            complete(deviceManifestUpdate.setDeviceManifest(ns, device, devMan))
          } ~
          entity(as[SignedPayload[LegacyDeviceManifest]]) { devMan =>
            complete(deviceManifestUpdate.setLegacyDeviceManifest(ns, device, devMan))
          }
        }
      } ~
      get {
        path(RoleType.JsonRoleTypeMetaPath) {
          case RoleType.ROOT => fetchRoot(ns)
          case RoleType.TARGETS =>
            val f = roles.fetchTargets(ns, device)
            complete(f)
          case RoleType.SNAPSHOT =>
            val f = roles.fetchSnapshot(ns, device)
            complete(f)
          case RoleType.TIMESTAMP =>
            val f = roles.fetchTimestamp(ns, device)
            complete(f)
        }
      }
    }
  }
}
