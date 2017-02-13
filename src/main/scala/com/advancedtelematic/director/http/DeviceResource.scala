package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.Crypto
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, SignedPayload}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheRepositorySupport}
import com.advancedtelematic.director.manifest.Verify
import org.genivi.sota.data.{Namespace, Uuid}
import org.genivi.sota.http.UuidDirectives.extractUuid
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._


class DeviceResource(extractNamespace: Directive1[Namespace],
                     verifier: Crypto => Verify.Verifier)
                    (implicit db: Database, ec: ExecutionContext, mat: Materializer)
    extends DeviceRepositorySupport
    with FileCacheRepositorySupport {
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

  def fetchTargets(ns: Namespace, device: Uuid, version: Int): Route = {
    complete(fileCacheRepository.fetchTarget(device, version))

  }

  def fetchSnapshots(ns: Namespace, device: Uuid, version: Int): Route = {
    complete(fileCacheRepository.fetchSnapshot(device, version))
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
          path(IntNumber ~ ".targets.json") { version =>
            fetchTargets(ns, device, version)
          } ~
          path(IntNumber ~ ".snapshots.json") { version =>
            fetchSnapshots(ns, device, version)
          }
        }
      }
    }
  }
}
