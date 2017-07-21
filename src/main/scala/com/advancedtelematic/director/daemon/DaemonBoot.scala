package com.advancedtelematic.director.daemon

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.director.db.SetMultiTargets
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.libtuf.keyserver.KeyserverHttpClient
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.http.{BootApp, HealthResource}
import com.advancedtelematic.libats.messaging.{MessageBus, MessageListener}
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.libats.messaging_datatype.Messages.{CampaignLaunched, UserCreated}
import com.advancedtelematic.libats.monitoring.MetricsSupport
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf.data.Messages.TufTargetAdded

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
  implicit val msgPublisher = MessageBus.publisher(system, config).fold(throw _, identity)

  log.info("Starting director daemon")

  val tuf = new KeyserverHttpClient(tufUri)

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props(tuf), "filecache-daemon")

  val createRepoWorker = new CreateRepoWorker(new DirectorRepo(tuf))
  val userCreatedBusListener = system.actorOf(MessageListener.props[UserCreated](config, createRepoWorker.action),
                                              "user-created-msg-listener")
  userCreatedBusListener ! Subscribe

  val campaignCreatedListener =
    system.actorOf(MessageListener.props[CampaignLaunched](config, CampaignWorker.action),
                   "campaign-created-msg-listener")
  campaignCreatedListener ! Subscribe

  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)
  val tufTargetAddedListener =
    system.actorOf(MessageListener.props[TufTargetAdded](config, tufTargetWorker.action),
                   "tuf-target-added-listener")
  tufTargetAddedListener ! Subscribe

  val routes: Route = (versionHeaders(version) & logResponseMetrics(projectName)) {
    new HealthResource(Seq(DbHealthResource.HealthCheck(db)), versionMap).route
  }

  Http().bindAndHandle(routes, host, port)
}
