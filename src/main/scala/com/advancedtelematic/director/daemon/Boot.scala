package com.advancedtelematic.director.daemon

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import com.advancedtelematic.director.{Settings, VersionInfo}

import org.genivi.sota.db.{BootMigrations, DatabaseConfig}
import org.genivi.sota.http.{BootApp, HealthResource}
import org.genivi.sota.monitoring.{DatabaseMetrics, MetricsSupport}

object DaemonBoot extends BootApp
    with Settings
    with VersionInfo
    with BootMigrations
    with DatabaseConfig
    with MetricsSupport
    with DatabaseMetrics {
  import org.genivi.sota.http.LogDirectives._
  import org.genivi.sota.http.VersionDirectives._

  implicit val _db = db

  log.info("Starting director daemon")

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props, "filecache-daemon")

  val routes: Route = (versionHeaders(version) & logResponseMetrics(projectName)) {
    new HealthResource(db, versionMap).route
  }

  Http().bindAndHandle(routes, host, port)
}
