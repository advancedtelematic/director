package com.advancedtelematic.director.daemon

import akka.Done
import akka.actor.ActorRef
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
import com.advancedtelematic.libats.http.{BootApp, HealthResource}
import com.advancedtelematic.libats.messaging.{MessageBus, MessageListener}
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.libats.messaging.Messages.MessageLike
import com.advancedtelematic.libats.messaging_datatype.Messages.{
  CampaignLaunched, BsDiffGenerationFailed, DeltaGenerationFailed, GeneratedDelta, GeneratedBsDiff, UserCreated}
import com.advancedtelematic.libats.monitoring.MetricsSupport
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf.data.Messages.TufTargetAdded
import scala.concurrent.Future

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

  implicit val msgPublisher = MessageBus.publisher(system, config).fold(throw _, identity)

  val tuf = new KeyserverHttpClient(tufUri)
  val diffService = new DiffServiceDirectorClient(tufBinaryUri)
  val rolesGeneration = new RolesGeneration(tuf, diffService)

  val fileCacheDaemon = system.actorOf(FileCacheDaemon.props(rolesGeneration), "filecache-daemon")

  def startMessageListener[T](action: T => Future[Done])(implicit ml: MessageLike[T]): ActorRef = {
    val actor = system.actorOf(MessageListener.props[T](config, action), ml.streamName + "-listener")
    actor ! Subscribe
    actor
  }

  val createRepoWorker = new CreateRepoWorker(new DirectorRepo(tuf))
  val userCreatedBusListener = startMessageListener[UserCreated](createRepoWorker.action)

  val campaignCreatedListener = startMessageListener[CampaignLaunched](CampaignWorker.action)

  val diffListener = new DiffListener
  val generatedDeltaListener = startMessageListener[GeneratedDelta](diffListener.generatedDeltaAction)
  val generatedBsDiffListener = startMessageListener[GeneratedBsDiff](diffListener.generatedBsDiffAction)
  val deltaGenerationFailedListener = startMessageListener[DeltaGenerationFailed](diffListener.deltaGenerationFailedAction)
  val bsDiffGenerationFailedListener = startMessageListener[BsDiffGenerationFailed](diffListener.bsDiffGenerationFailedAction)

  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)
  val tufTargetAddedListener = startMessageListener[TufTargetAdded](tufTargetWorker.action)

  val routes: Route = (versionHeaders(version) & logResponseMetrics(projectName)) {
    new HealthResource(Seq(DbHealthResource.HealthCheck(db)), versionMap).route
  }

  Http().bindAndHandle(routes, host, port)
}
