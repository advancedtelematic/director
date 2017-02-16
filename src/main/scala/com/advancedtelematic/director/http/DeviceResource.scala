package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.Crypto
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, Errors => DBErrors, FileCacheRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.director.manifest.Verify
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.http.UuidDirectives.extractUuid
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.slf4j.LoggerFactory
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._


class DeviceResource(extractNamespace: Directive1[Namespace],
                     verifier: Crypto => Verifier)
                    (implicit db: Database, ec: ExecutionContext, mat: Materializer)
    extends DeviceRepositorySupport
    with AdminRepositorySupport
    with FileCacheRepositorySupport {
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.Route

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def updateCurrentTarget(namespace: Namespace, device: Uuid, ecuManifests: Seq[EcuManifest]): Future[Unit] =
    deviceRepository.getNextVersion(device).flatMap { next_version =>
      async {
        val next_version = await(deviceRepository.getNextVersion(device))

        val targets = await(db.run(adminRepository.fetchTargetVersion(namespace, device, next_version)))

        val translatedManifest = ecuManifests.groupBy(_.ecu_serial).mapValues(_.head.installed_image)

        if (targets == translatedManifest) {
          await(deviceRepository.updateDeviceVersion(device, next_version))
        } else {
          _log.error(s"Device $device updated to the wrong target")
          await(FastFuture.failed(Errors.DeviceUpdatedToWrongTarget))
        }
      }
    }.recoverWith {
      case DBErrors.MissingSnapshot =>
        deviceRepository.updateDeviceVersion(device, 0)
    }

  def setDeviceManifest(namespace: Namespace, signedDevMan: SignedPayload[DeviceManifest]): Route = {
    val device = signedDevMan.signed.vin
    val action =
      deviceRepository.findEcus(namespace, device).flatMap { case ecus =>
        Verify.deviceManifest(ecus, verifier, signedDevMan) match {
          case Failure(reason) => FastFuture.failed(reason)
          case Success(ecuImages) =>
            deviceRepository.persistAll(ecuImages).flatMap(_ => updateCurrentTarget(namespace, device, ecuImages))
        }
      }
    complete(action)
  }

  def fetchTargets(ns: Namespace, device: Uuid): Route = {
    val action = deviceRepository.getNextVersion(device).flatMap { version =>
      fileCacheRepository.fetchTarget(device, version)
    }
    complete(action)
  }

  def fetchSnapshots(ns: Namespace, device: Uuid): Route = {
    val action = deviceRepository.getNextVersion(device).flatMap { version =>
      fileCacheRepository.fetchSnapshot(device, version)
    }
    complete(action)
  }

  val route = extractNamespace { ns =>
    pathPrefix("mydevice") {
      path("manifest") {
        (put & entity(as[SignedPayload[DeviceManifest]])) { devMan =>
          setDeviceManifest(ns, devMan)
        }
      } ~
      extractUuid { device =>
        get {
          path("targets.json") {
            fetchTargets(ns, device)
          } ~
          path("snapshots.json") {
            fetchSnapshots(ns, device)
          }
        }
      }
    }
  }
}
