package com.advancedtelematic.director.util

import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.director.data.DataType.Crypto
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.director.manifest.Verify
import org.genivi.sota.core.DatabaseSpec
import org.scalatest.Suite

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec {
  self: Suite =>

  def apiUri(path: String): String = "/api/v1/" + path

  def routesWithVerifier(verifier: Crypto => Verify.Verifier) = new DirectorRoutes(verifier).routes

  lazy val routes = routesWithVerifier(_ => Verify.alwaysAccept)
}


