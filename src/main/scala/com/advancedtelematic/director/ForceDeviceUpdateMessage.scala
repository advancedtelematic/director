package com.advancedtelematic.director

import java.time.Instant
import java.util.UUID

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}
import com.advancedtelematic.director.data.DbDataType.ProcessedAssignment
import com.advancedtelematic.director.db.AssignmentsRepositorySupport
import com.advancedtelematic.libats.data.DataType.{Namespace, ResultCode, ResultDescription}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.messaging.{MessageBus, MessageBusPublisher}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateCompleted, DeviceUpdateEvent}
import com.advancedtelematic.libats.slick.db.DatabaseConfig
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try
import scala.concurrent.duration._

protected [director] class ForceDeviceUpdateMessage()(implicit system: ActorSystem, mat: ActorMaterializer, msgBus: MessageBusPublisher, val ec: ExecutionContext, val db: Database) extends AssignmentsRepositorySupport {
  private val _log = LoggerFactory.getLogger(this.getClass)

  def run(ns: Namespace, since: Instant, deviceIds: Set[DeviceId], dryRun: Boolean): Future[Int] = {
    if(dryRun)
      _log.warn("Will not publish any messages to bus, to publish messages use --force")

    val assignmentsSource = assignmentsRepository.streamProcessed(ns, since, deviceIds)

    val flow = Flow[(ProcessedAssignment, Instant)].mapAsync(5) { case (assignment, createdAt) =>
      val result = if(!assignment.successful) {
        InstallationResult(success = false, ResultCode("19"), ResultDescription("One or more targeted ECUs failed to update"))
      } else {
        InstallationResult(success = true, ResultCode("0"), ResultDescription("All targeted ECUs were successfully updated"))
      }

      val msg = DeviceUpdateCompleted(assignment.ns, createdAt, assignment.correlationId, assignment.deviceId, result, Map.empty)

      if (dryRun) {
        _log.warn(s"Not publishing $msg")
        FastFuture.successful(())
      } else {
        _log.info(s"Publishing $msg to bus")
        msgBus.publishSafe[DeviceUpdateEvent](msg).map(_ => ())
      }
    }

    assignmentsSource.via(flow).runFold(0) { case (c, _) => c + 1}
  }
}

object ForceDeviceUpdateMessage extends BootApp with Settings with VersionInfo with AssignmentsRepositorySupport with DatabaseConfig {
  implicit val msgPublisher = MessageBus.publisher(system, config)

  override val ec: ExecutionContext = exec

  implicit val _db = db

  if(args.length < 2) {
    log.error("usage: <namespace> <since instant> [--force]")
    system.terminate()
    sys.exit(1)
  }

  try {
    val ns = Namespace(args(0))
    val since = Instant.parse(args(1))
    val force = Try(args(2)).toOption.contains("--force")
    val devices = args.drop(3).map { str => DeviceId(UUID.fromString(str)) }.toSet

    log.info(s"dryRun=${!force} devices=${devices} since=${since} namespace=$ns")

    val f = new ForceDeviceUpdateMessage().run(ns, since, devices, dryRun = !force)

    val count = Await.result(f, Duration.Inf)

    log.info(s"Finished. $count processed assignments found")

    system.terminate()

  } catch { case ex: Throwable =>
    log.error("Error", ex)
    system.terminate()
    sys.exit(1)
  }
}
