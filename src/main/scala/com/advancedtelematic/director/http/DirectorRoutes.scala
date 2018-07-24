package com.advancedtelematic.director.http

import akka.http.scaladsl.server.{Directives, _}
import com.advancedtelematic.diff_service.client.DiffServiceClient
import com.advancedtelematic.diff_service.http.DiffResource
import com.advancedtelematic.director.VersionInfo
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.director.roles.Roles
import com.advancedtelematic.libats.http.ErrorHandler
import com.advancedtelematic.libats.http.DefaultRejectionHandler.rejectionHandler
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.ValidEcuSerial
import com.advancedtelematic.libtuf.data.TufDataType.{TargetName, TufKey}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

import com.advancedtelematic.libats.data.RefinedUtils._


object DirectorRoutes {
  import Directives._

  val EcuSerialPath = Segment.flatMap(_.refineTry[ValidEcuSerial].toOption)
  val TargetNamePath: PathMatcher1[TargetName] = Segment.map(TargetName.apply)
}


class DirectorRoutes(verifier: TufKey => Verifier,
                     coreClient: CoreClient,
                     keyserverClient: KeyserverClient,
                     roles: Roles,
                     diffService: DiffServiceClient)
                    (implicit val db: Database,
                     ec: ExecutionContext,
                     messageBusPublisher: MessageBusPublisher) extends VersionInfo {
  import Directives._

  val extractNamespace = NamespaceDirectives.defaultNamespaceExtractor

  val routes: Route =
    handleRejections(rejectionHandler) {
      ErrorHandler.handleErrors {
        pathPrefix("api" / "v1") {
          new AdminResource(extractNamespace, keyserverClient).route ~
          new DeviceResource(extractNamespace, verifier, coreClient, keyserverClient, roles).route ~
          new MultiTargetUpdatesResource(extractNamespace).route ~
          new DiffResource(extractNamespace, diffService).route
        }
      }
    }
}
