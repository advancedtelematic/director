package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{DeviceId, FileCacheRequest, Namespace}
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport,
  FileCacheRequestRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.repo_store.RoleKeyStoreClient
import com.advancedtelematic.libats.codecs.AkkaCirce._
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import scala.concurrent.ExecutionContext
import scala.async.Async._
import slick.driver.MySQLDriver.api._

class AdminResource(extractNamespace: Directive1[Namespace], tuf: RoleKeyStoreClient)
                   (implicit db: Database, ec: ExecutionContext, mat: Materializer)
    extends AdminRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRequestRepositorySupport
    with RepoNameRepositorySupport {

  def registerDevice(namespace: Namespace, regDev: RegisterDevice): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete( StatusCodes.BadRequest ->
                              s"The primary ecu: ${primEcu.get} isn't part of the list of ECUs") // TODO: Should fail with a well known error code and just leave that to be thrown by akka
      case Some(_) => complete( StatusCodes.Created ->
                                 adminRepository.createDevice(namespace, regDev.vin, primEcu, regDev.ecus))
    }
  }

  def listInstalledImages(namespace: Namespace, device: DeviceId): Route = {
    complete(adminRepository.findImages(namespace, device))
  }

  def setTargets(namespace: Namespace, device: DeviceId, targets: SetTarget): Route = {
    val act = async {
      val ecus = await(deviceRepository.findEcus(namespace, device)).map(_.ecuSerial).toSet

      if (!targets.updates.keys.toSet.subsetOf(ecus)) {
        await(FastFuture.failed(Errors.TargetsNotSubSetOfDevice))
      }

      val new_version = await(adminRepository.updateTarget(namespace, device, targets.updates))

      await(fileCacheRequestRepository.persist(FileCacheRequest(namespace, new_version, device, FileCacheRequestStatus.PENDING)))

    }
    complete(act)
  }

  def registerNamespace(namespace: Namespace): Route = {
    val repo = RepoId.generate
    val act = for {
      _ <- tuf.createRoot(repo)
      _ <- repoNameRepository.storeRepo(namespace, repo)
    } yield repo

    complete(act)
  }

  val route = extractNamespace { ns =>
    pathPrefix("admin") {
      pathEnd {
        post { registerNamespace(ns) }
      } ~
      (post & path("devices") & entity(as[RegisterDevice]))  { regDev =>
        registerDevice(ns, regDev)
      } ~
      pathPrefix(DeviceId.Path) { dev =>
        (get & path("images")) {
          listInstalledImages(ns, dev)
        } ~
        (put & path("targets") & entity(as[SetTarget])) { targets =>
          setTargets(ns, dev, targets)
        }
      }
    }
  }
}
