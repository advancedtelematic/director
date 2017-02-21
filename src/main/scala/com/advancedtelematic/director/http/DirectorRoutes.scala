package com.advancedtelematic.director.http

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{Directives, _}
import akka.stream.Materializer
import com.advancedtelematic.director.VersionInfo
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.repo_store.RoleKeyStoreClient
import com.advancedtelematic.libats.http.{ErrorHandler, HealthResource}
import com.advancedtelematic.libats.http.DefaultRejectionHandler.rejectionHandler

import scala.concurrent.ExecutionContext
import slick.driver.MySQLDriver.api._


class DirectorRoutes(verifier: ClientKey => Verifier, tuf: RoleKeyStoreClient)
                    (implicit val db: Database,
                     ec: ExecutionContext,
                     sys: ActorSystem,
                     mat: Materializer) extends VersionInfo {
  import Directives._

  val extractNamespace = NamespaceDirectives.defaultNamespaceExtractor

  val routes: Route =
    handleRejections(rejectionHandler) {
      ErrorHandler.handleErrors {
        pathPrefix("api" / "v1") {
          new AdminResource(extractNamespace, tuf).route ~
          new DeviceResource(extractNamespace, verifier).route
        } ~ new HealthResource(db, versionMap).route
      }
    }
}
