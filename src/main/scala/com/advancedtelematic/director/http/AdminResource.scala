package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.AdminRequest.{RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport,
  FileCacheRequestRepositorySupport}
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.http.UuidDirectives.extractUuid
import scala.concurrent.ExecutionContext
import scala.async.Async._
import slick.driver.MySQLDriver.api._

class AdminResource(extractNamespace: Directive1[Namespace])
                   (implicit db: Database, ec: ExecutionContext, mat: Materializer)
    extends AdminRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRequestRepositorySupport {

  def registerDevice(namespace: Namespace, regDev: RegisterDevice): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete( StatusCodes.BadRequest ->
                              s"The primary ecu: ${primEcu.get} isn't part of the list of ECUs")
      case Some(_) => complete( StatusCodes.Created ->
                                 adminRepository.createDevice(namespace, regDev.vin, primEcu, regDev.ecus))
    }
  }

  def listInstalledImages(namespace: Namespace, device: Uuid): Route = {
    complete(adminRepository.findImages(namespace, device))
  }

  def setTargets(namespace: Namespace, device: Uuid, targets: SetTarget): Route = {
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

  val route = extractNamespace { ns =>
    pathPrefix("admin") {
      (post & pathEnd & entity(as[RegisterDevice]))  { regDev =>
        registerDevice(ns, regDev)
      } ~
      (get & extractUuid & path("installed_images")) { dev =>
        listInstalledImages(ns, dev)
      } ~
      (put & extractUuid & path("set_targets") & entity(as[SetTarget])) { (dev, targets) =>
        setTargets(ns, dev, targets)
      }
    }
  }
}
