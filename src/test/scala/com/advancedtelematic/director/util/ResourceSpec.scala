package com.advancedtelematic.director.util

import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.diff_service.client.FakeDiffServiceClient
import com.advancedtelematic.director.Settings
import com.advancedtelematic.director.client.{FakeCoreClient, FakeKeyserverClient}
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.director.manifest.Verifier
import com.advancedtelematic.director.roles.{Roles, RolesGeneration}
import com.advancedtelematic.director.util.NamespaceTag.NamespaceTag
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.TufDataType.TufKey
import org.scalatest.Suite

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec with Settings {
  self: Suite =>

  def apiUri(path: String): String = "/api/v1/" + path

  val defaultNs = Namespace("default")
  val defaultNsTag = new NamespaceTag{ val value = defaultNs.get }

  val coreClient = FakeCoreClient
  val diffServiceClient = new FakeDiffServiceClient(tufBinaryUri)

  implicit val msgPub = MessageBusPublisher.ignore
}

trait RouteResourceSpec extends ResourceSpec {
  self: Suite =>

  val keyserverClient: FakeKeyserverClient
  val rolesGeneration = new RolesGeneration(keyserverClient, diffServiceClient)
  val roles = new Roles(rolesGeneration)
  def routesWithVerifier(verifier: TufKey => Verifier.Verifier) =
    new DirectorRoutes(verifier, coreClient, keyserverClient, roles, diffServiceClient).routes

  lazy val routes = routesWithVerifier(_ => Verifier.alwaysAccept)
}
