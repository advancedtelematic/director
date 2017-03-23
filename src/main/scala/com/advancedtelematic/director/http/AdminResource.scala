package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{DeviceId, Namespace}
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, FileCacheRequestRepositorySupport, RootFilesRepositorySupport,
  SetTargets}
import com.advancedtelematic.libats.codecs.AkkaCirce._
import de.heikoseeberger.akkahttpcirce.CirceSupport._

import scala.concurrent.ExecutionContext
import scala.async.Async._
import slick.driver.MySQLDriver.api._

class AdminResource(extractNamespace: Directive1[Namespace])
                   (implicit db: Database, ec: ExecutionContext, mat: Materializer)
    extends AdminRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRequestRepositorySupport
    with RootFilesRepositorySupport {

  def registerDevice(namespace: Namespace, regDev: RegisterDevice): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete( Errors.PrimaryIsNotListedForDevice )
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
      } else {
        await(SetTargets.setTargets(namespace, Seq(device -> targets)))
      }
    }
    complete(act)
  }

  def fetchRoot(namespace: Namespace): Route = {
    complete(rootFilesRepository.find(namespace))
  }

  val route: Route = extractNamespace { ns =>
    pathPrefix("admin") {
      (get & path("root.json")) {
         fetchRoot(ns)
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
