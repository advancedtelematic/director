package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.db.DeviceRepositorySupport
import com.advancedtelematic.director.manifest.Verify
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.http.UuidDirectives.extractUuid
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._


class DeviceResource(extractNamespace: Directive1[Namespace], verifier: Crypto => Verify.Verifier)
  (implicit db: Database, ec: ExecutionContext, mat: Materializer) extends DeviceRepositorySupport {
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.Route

  def setDeviceManifest(namespace: Namespace, signedDevMan: SignedPayload[DeviceManifest]): Route = {
    import akka.http.scaladsl.marshalling.ToResponseMarshallable
    val action: Future[ToResponseMarshallable] =
      deviceRepository.findEcus(namespace, signedDevMan.signed.vin).flatMap { case ecus =>
        Verify.deviceManifest(ecus, verifier, signedDevMan) match {
          case Failure(reason) => FastFuture.failed(reason)
          case Success(ecuImages) => deviceRepository.persistAll(ecuImages).map(_ => ())
        }
      }
    complete(action)
  }

  def registerDevice(namespace: Namespace, regDev: RegisterDevice): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete( StatusCodes.BadRequest -> s"The primary ecu: ${primEcu.get} isn't part of the list of ECUs")
      case Some(_) => complete( StatusCodes.Created -> deviceRepository.createDevice(namespace, regDev.vin, primEcu, regDev.ecus))
    }
  }

  def listInstalledImages(namespace: Namespace, device: Uuid): Route = {
    complete(deviceRepository.findImages(namespace, device))
  }

  val deviceRoutes = extractNamespace { ns =>
    pathPrefix("device") {
      (post & pathEnd & entity(as[RegisterDevice]))  { regDev =>
        registerDevice(ns, regDev)
      } ~
      (get & extractUuid & path("installed_images")) { dev =>
        listInstalledImages(ns, dev)
      }

    }
  }

  val mydeviceRoutes = extractNamespace { ns =>
    path("mydevice" / "manifest") {
      (put & entity(as[SignedPayload[DeviceManifest]])) { devMan =>
        setDeviceManifest(ns, devMan)
      }
    }
  }

  val route = deviceRoutes ~ mydeviceRoutes
}
