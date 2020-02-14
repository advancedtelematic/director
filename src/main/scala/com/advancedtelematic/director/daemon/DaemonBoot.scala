package com.advancedtelematic.director.daemon

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.diff_service.client.DiffServiceDirectorClient
import com.advancedtelematic.diff_service.daemon.DiffListener
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.director.db.SetMultiTargets
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.roles.RolesGeneration
import com.advancedtelematic.libats.slick.db.{BootMigrations, CheckMigrations, DatabaseConfig}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.tracing.NullServerRequestTracing
import com.advancedtelematic.libats.messaging.{BusListenerMetrics, MessageBus, MessageListenerSupport}
import com.advancedtelematic.libats.messaging_datatype.Messages.{BsDiffGenerationFailed, DeltaGenerationFailed, GeneratedBsDiff, GeneratedDelta, UserCreated}
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf_server.data.Messages._
import com.advancedtelematic.libtuf_server.keyserver.KeyserverHttpClient
import com.advancedtelematic.metrics.{AkkaHttpRequestMetrics, MetricsSupport, MonitoredBusListenerSupport}
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport

object DaemonBoot extends BootApp
    with Directives
    with Settings
    with VersionInfo
    with BootMigrations
    with DatabaseConfig
    with MetricsSupport
    with DatabaseMetrics
    with MessageListenerSupport
    with AkkaHttpRequestMetrics
    with CheckMigrations
    with PrometheusMetricsSupport with MonitoredBusListenerSupport {

  implicit val _db = db

  log.info("Starting director daemon")

  implicit val msgPublisher = MessageBus.publisher(system, config)

  val tuf = KeyserverHttpClient(tufUri)(system, materializer, new NullServerRequestTracing)
  val diffService = new DiffServiceDirectorClient(tufBinaryUri)
  val rolesGeneration = new RolesGeneration(tuf, diffService)

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props(rolesGeneration), "filecache-daemon")

  val userCreatedBusListener = defaultKeyType.map { kt =>
    log.info(s"default key type: $kt")
    val createRepoWorker = new CreateRepoWorker(new DirectorRepo(tuf), kt)
    startMonitoredListener[UserCreated](createRepoWorker.action)
  }

  val diffListener = new DiffListener
  val generatedDeltaListener = startMonitoredListener[GeneratedDelta](diffListener.generatedDeltaAction)
  val generatedBsDiffListener = startMonitoredListener[GeneratedBsDiff](diffListener.generatedBsDiffAction)
  val deltaGenerationFailedListener = startMonitoredListener[DeltaGenerationFailed](diffListener.deltaGenerationFailedAction)
  val bsDiffGenerationFailedListener = startMonitoredListener[BsDiffGenerationFailed](diffListener.bsDiffGenerationFailedAction)

  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)
  val tufTargetAddedListener = startMonitoredListener[TufTargetAdded](tufTargetWorker.action)

  val routes: Route = (versionHeaders(version) & requestMetrics(metricRegistry) & logResponseMetrics(projectName)) {
    prometheusMetricsRoutes ~
    DbHealthResource(versionMap, healthMetrics = Seq(new BusListenerMetrics(metricRegistry))).route
  }

  Http().bindAndHandle(routes, host, port)
}
