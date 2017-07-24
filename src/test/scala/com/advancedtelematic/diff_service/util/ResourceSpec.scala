package com.advancedtelematic.diff_service.util

import akka.http.scaladsl.server.AuthorizationFailedRejection
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.diff_service.client.DiffServiceDirectorClient
import com.advancedtelematic.diff_service.daemon.DiffListener
import com.advancedtelematic.diff_service.http.DiffResource
import com.advancedtelematic.director.Settings
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.http.DefaultRejectionHandler.rejectionHandler
import com.advancedtelematic.libats.http.ErrorHandler
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.test.DatabaseSpec
import org.scalatest.Suite

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec with Settings {
  self: Suite =>

  def apiUri(path: String): String = "/api/v1/" + path

  val defaultNs = Namespace("default")

  implicit val msgBusPublisher = MessageBusPublisher.ignore
  val diffServiceClient = new DiffServiceDirectorClient(tufBinaryUri)
  val diffListener = new DiffListener

  lazy val extractNamespace: Directive1[Namespace] =
    optionalHeaderValueByName("x-ats-namespace").flatMap {
      case None => reject(AuthorizationFailedRejection)
      case Some(ns) => provide(Namespace(ns))
    }

  lazy val routes = handleRejections(rejectionHandler) {
    ErrorHandler.handleErrors {
      pathPrefix("api" / "v1") {
        new DiffResource(extractNamespace, diffServiceClient).route
      }
    }
  }
}
