package com.advancedtelematic.director.daemon

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.director.db.SetMultiTargets
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.roles.RolesGeneration
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.http.tracing.NullRequestTracing
import com.advancedtelematic.libats.messaging.{BusListenerMetrics, MessageBus, MessageListenerSupport}
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf_server.data.Messages._
import com.advancedtelematic.libtuf_server.keyserver.KeyserverHttpClient
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport
import com.advancedtelematic.metrics.{AkkaHttpRequestMetrics, InfluxdbMetricsReporterSupport}

object DaemonBoot extends BootApp
    with Directives
    with Settings
    with VersionInfo
    with BootMigrations
    with DatabaseConfig
    with MetricsSupport
    with DatabaseMetrics
    with MessageListenerSupport
    with InfluxdbMetricsReporterSupport
    with AkkaHttpRequestMetrics
    with PrometheusMetricsSupport {

  implicit val _db = db

  log.info("Starting director daemon")

  implicit val msgPublisher = MessageBus.publisher(system, config)

  val tuf = KeyserverHttpClient(tufUri)(system, materializer, new NullRequestTracing)
  val rolesGeneration = new RolesGeneration(tuf)

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props(rolesGeneration), "filecache-daemon")

  val userCreatedBusListener = defaultKeyType.map { kt =>
    log.info(s"default key type: $kt")
    val createRepoWorker = new CreateRepoWorker(new DirectorRepo(tuf), kt)
    startListener[UserCreated](createRepoWorker.action)
  }

  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)
  val tufTargetAddedListener = startListener[TufTargetAdded](tufTargetWorker.action)

  val routes: Route = (versionHeaders(version) & requestMetrics(metricRegistry) & logResponseMetrics(projectName)) {
    prometheusMetricsRoutes ~
    DbHealthResource(versionMap, healthMetrics = Seq(new BusListenerMetrics(metricRegistry))).route
  }

  Http().bindAndHandle(routes, host, port)
}
