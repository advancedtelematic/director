package com.advancedtelematic.director.daemon

import akka.actor.Status.{Failure, Success}
import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern.pipe
import cats.syntax.show.toShowOps
import com.advancedtelematic.director.daemon.FileCacheDaemon.Tick
import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus.{ERROR, SUCCESS}
import com.advancedtelematic.director.db.FileCacheRequestRepositorySupport
import com.advancedtelematic.director.roles.RolesGeneration
import com.advancedtelematic.libtuf.keyserver.KeyserverClient

import scala.concurrent.duration._
import slick.driver.MySQLDriver.api._

object FileCacheDaemon {
  case object Tick

  def props(tuf: KeyserverClient)(implicit db: Database):Props = Props(new FileCacheDaemon(tuf))
}

class FileCacheDaemon(tuf: KeyserverClient)(implicit val db: Database) extends Actor
    with ActorLogging
    with FileCacheRequestRepositorySupport {

  implicit val ec = context.dispatcher

  override def preStart(): Unit = {
    self ! Tick
  }

  private val worker = {
    context.system.actorOf(FileCacheWorker.props(tuf))
  }

  def waiting(totalTasks: Int, remaining: Int): Receive =
    if (remaining == 0) {
      log.info("Finished caching {} files", totalTasks)
      context.system.scheduler.scheduleOnce(3.seconds, self, Tick)
      receive
    } else {
      case Status.Success(_) =>
        context.become(waiting(totalTasks, remaining - 1))
      case Status.Failure(ex) =>
        log.error(ex, "Could not cache file")
        context.become(waiting(totalTasks, remaining - 1))
    }

  override def receive: Receive = {
    case Status.Failure(ex) =>
      throw ex

    case taskCount: Int =>
      log.info("Waiting for {} file cache tasks to complete", taskCount)
      context become waiting(taskCount, taskCount)

    case Tick =>
      log.info("Tick")

      val f = fileCacheRequestRepository.findPending().map { m =>
        m.foreach { worker ! _}
        m.size
      }

      f.pipeTo(self)
  }
}

object FileCacheWorker {
  def props(tuf: KeyserverClient)(implicit db: Database): Props = Props(new FileCacheWorker(tuf))
}

class FileCacheWorker(tuf: KeyserverClient)(implicit val db: Database) extends Actor
    with ActorLogging
    with FileCacheRequestRepositorySupport {

  implicit val ec = context.dispatcher
  val rolesGeneration = new RolesGeneration(tuf)


  override def receive: Receive = {
    case fcr: FileCacheRequest =>
      log.info("Received file cache request for {} targetVersion: {} timestampVersion: {}",
               fcr.device.show, fcr.targetVersion, fcr.timestampVersion)

      rolesGeneration.processFileCacheRequest(fcr)
        .flatMap { _ => fileCacheRequestRepository.updateRequest(fcr.copy(status = SUCCESS)) }
        .map(Success)
        .recoverWith {
          case ex =>
            log.error("File cache failed: {}", ex.getMessage)
            fileCacheRequestRepository.updateRequest(fcr.copy(status = ERROR)).map(_ => Failure(ex))
        }.pipeTo(sender)
  }
}
