package com.advancedtelematic.director.daemon

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.advancedtelematic.diff_service.client.DiffServiceDirectorClient
import com.advancedtelematic.diff_service.daemon.DiffListener
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.director.db.SetMultiTargets
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.roles.RolesGeneration
import com.advancedtelematic.libtuf.keyserver.KeyserverHttpClient
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.messaging.{BusListenerMetrics, MessageBus, MessageListenerSupport}
import com.advancedtelematic.libats.messaging_datatype.Messages.{BsDiffGenerationFailed, CampaignLaunched, DeltaGenerationFailed, GeneratedBsDiff, GeneratedDelta, UserCreated}
import com.advancedtelematic.libats.monitoring.MetricsSupport
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf.data.Messages.TufTargetAdded
import com.advancedtelematic.metrics.{AkkaHttpMetricsSink, InfluxDbMetricsReporter}

object DaemonBoot extends BootApp
    with Settings
    with VersionInfo
    with BootMigrations
    with DatabaseConfig
    with MetricsSupport
    with DatabaseMetrics
    with MessageListenerSupport {

  import com.advancedtelematic.libats.http.LogDirectives._
  import com.advancedtelematic.libats.http.VersionDirectives._

  implicit val _db = db

  log.info("Starting director daemon")

  implicit val msgPublisher = MessageBus.publisher(system, config).fold(throw _, identity)

  val tuf = KeyserverHttpClient(tufUri)
  val diffService = new DiffServiceDirectorClient(tufBinaryUri)
  val rolesGeneration = new RolesGeneration(tuf, diffService)

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props(rolesGeneration), "filecache-daemon")

  val createRepoWorker = new CreateRepoWorker(new DirectorRepo(tuf))
  val userCreatedBusListener = startListener[UserCreated](createRepoWorker.action)

  val campaignCreatedListener = startListener[CampaignLaunched](CampaignWorker.action)

  val diffListener = new DiffListener
  val generatedDeltaListener = startListener[GeneratedDelta](diffListener.generatedDeltaAction)
  val generatedBsDiffListener = startListener[GeneratedBsDiff](diffListener.generatedBsDiffAction)
  val deltaGenerationFailedListener = startListener[DeltaGenerationFailed](diffListener.deltaGenerationFailedAction)
  val bsDiffGenerationFailedListener = startListener[BsDiffGenerationFailed](diffListener.bsDiffGenerationFailedAction)

  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)
  val tufTargetAddedListener = startListener[TufTargetAdded](tufTargetWorker.action)

  metricsReporterSettings.foreach{ reporterSettings =>
    InfluxDbMetricsReporter.start(reporterSettings, metricRegistry, AkkaHttpMetricsSink.apply(reporterSettings))
  }

  val routes: Route = (versionHeaders(version) & logResponseMetrics(projectName)) {
    DbHealthResource(versionMap, healthMetrics = Seq(new BusListenerMetrics(metricRegistry))).route
  }

  Http().bindAndHandle(routes, host, port)
}
