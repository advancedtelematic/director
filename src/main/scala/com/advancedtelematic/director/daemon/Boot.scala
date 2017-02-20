package com.advancedtelematic.director.daemon

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.libtuf.repo_store.RoleKeyStoreHttpClient

import com.advancedtelematic.libats.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.http.{BootApp, HealthResource}
import com.advancedtelematic.libats.monitoring.{DatabaseMetrics, MetricsSupport}

object DaemonBoot extends BootApp
    with Settings
    with VersionInfo
    with BootMigrations
    with DatabaseConfig
    with MetricsSupport
    with DatabaseMetrics {
  import com.advancedtelematic.libats.http.LogDirectives._
  import com.advancedtelematic.libats.http.VersionDirectives._

  implicit val _db = db

  log.info("Starting director daemon")

  val tuf = new RoleKeyStoreHttpClient(tufUri)

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props(tuf), "filecache-daemon")

  val routes: Route = (versionHeaders(version) & logResponseMetrics(projectName)) {
    new HealthResource(db, versionMap).route
  }

  Http().bindAndHandle(routes, host, port)
}
