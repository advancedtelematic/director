package com.advancedtelematic.director

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.director.manifest.SignatureVerification
import com.typesafe.config.ConfigFactory
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.genivi.sota.db.{BootMigrations, DatabaseConfig}
import org.genivi.sota.http.BootApp
import org.genivi.sota.http.LogDirectives.logResponseMetrics
import org.genivi.sota.http.VersionDirectives.versionHeaders
import org.genivi.sota.monitoring.{DatabaseMetrics, MetricsSupport}


trait Settings {
  lazy val config = ConfigFactory.load()

  val host = config.getString("server.host")
  val port = config.getInt("server.port")
}

object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with DatabaseConfig
  with BootMigrations
  with MetricsSupport
  with DatabaseMetrics {

  implicit val _db = db

  log.info(s"Starting $version on http://$host:$port")

  Security.addProvider(new BouncyCastleProvider())

  val routes: Route =
    (versionHeaders(version) & logResponseMetrics(projectName)) {
      new DirectorRoutes(SignatureVerification.verify).routes
    }

  Http().bindAndHandle(routes, host, port)
}
