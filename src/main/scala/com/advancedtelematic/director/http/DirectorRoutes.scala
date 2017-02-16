package com.advancedtelematic.director.http

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{Directives, _}
import akka.stream.Materializer
import com.advancedtelematic.director.data.DataType.Crypto
import com.advancedtelematic.director.VersionInfo
import com.advancedtelematic.director.manifest.Verifier.Verifier
import org.genivi.sota.http.{ErrorHandler, NamespaceDirectives, HealthResource}
import org.genivi.sota.rest.SotaRejectionHandler._

import scala.concurrent.ExecutionContext
import slick.driver.MySQLDriver.api._


class DirectorRoutes(verifier: Crypto => Verifier)
                    (implicit val db: Database,
                     ec: ExecutionContext,
                     sys: ActorSystem,
                     mat: Materializer) extends VersionInfo {
  import Directives._

  val extractNamespace = NamespaceDirectives.defaultNamespaceExtractor.map(_.namespace)

  val routes: Route =
    handleRejections(rejectionHandler) {
      ErrorHandler.handleErrors {
        pathPrefix("api" / "v1") {
          new AdminResource(extractNamespace).route ~
          new DeviceResource(extractNamespace, verifier).route
        } ~ new HealthResource(db, versionMap).route
      }
    }
}
