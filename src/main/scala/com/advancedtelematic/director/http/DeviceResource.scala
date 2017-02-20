package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{Crypto, DeviceId, Namespace}
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest}
import com.advancedtelematic.director.db.{AdminRepositorySupport, DeviceRepositorySupport, Errors => DBErrors, FileCacheRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.director.manifest.Verify
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import org.slf4j.LoggerFactory
import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
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

  // This will not work with concurrent requests, because of the version increment
  // getting and setting the version needs to be done on the same transaction
  def updateCurrentTarget(namespace: Namespace, device: DeviceId, ecuManifests: Seq[EcuManifest]): Future[Unit] =
      async {
        val next_version = await(deviceRepository.getNextVersion(device))
        val version = await(deviceRepository.getNextVersion(device))

        val targets = await(adminRepository.fetchTargetVersion(namespace, device, next_version))

        val translatedManifest = ecuManifests.groupBy(_.ecu_serial).mapValues(_.head.installed_image)

        if (targets == translatedManifest) {
          await(deviceRepository.updateDeviceVersion(device, next_version))
        } else {
          _log.info {
            s"""version : $version
               |targets : $targets
               |manifest: $translatedManifest
             """.stripMargin
          }

          _log.error(s"Device $device updated to the wrong target")
          await(FastFuture.failed(Errors.DeviceUpdatedToWrongTarget))
        }
      }.recoverWith {
        case DBErrors.MissingCurrentTarget =>
          deviceRepository.updateDeviceVersion(device, 0)
      }

  def setDeviceManifest(namespace: Namespace, signedDevMan: SignedPayload[DeviceManifest]): Route = {
    val device = signedDevMan.signed.vin
    val action = async {
      val ecus = await(deviceRepository.findEcus(namespace, device))
      val ecuImages = await(Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan)))

      // TODO: These two should probably be transactional??
      await(deviceRepository.persistAll(ecuImages))
      await(updateCurrentTarget(namespace, device, ecuImages))
    }
    complete(action)
  }

  def fetchTargets(ns: Namespace, device: DeviceId): Route = {
    // TODO: Assuming versions are monotonically increasing, then we should be able to just get the highest one?
    val action = deviceRepository.getNextVersion(device).flatMap { version =>
      fileCacheRepository.fetchTarget(device, version)
    }
    complete(action)
  }

  def fetchSnapshot(ns: Namespace, device: DeviceId): Route = {
    val action = deviceRepository.getNextVersion(device).flatMap { version =>
      fileCacheRepository.fetchSnapshot(device, version)
    }
    complete(action)
  }

  val route = extractNamespace { ns =>
    pathPrefix("device") {
      path("manifest") {
        (put & entity(as[SignedPayload[DeviceManifest]])) { devMan =>
          setDeviceManifest(ns, devMan)

          // ^^ This will return an empty response to device
          // When does the device receive an updated timestamp.json?
        }
      } ~
      pathPrefix(DeviceId.Path) { device =>
        get {
          path("targets.json") {
            fetchTargets(ns, device)
          } ~
          path("snapshots.json") {
            fetchSnapshot(ns, device)
          }
        }
      }
    }
  }
}
