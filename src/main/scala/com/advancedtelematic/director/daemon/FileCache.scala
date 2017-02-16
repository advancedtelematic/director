package com.advancedtelematic.director.daemon

import akka.actor.Status.{Failure, Success}
import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern.pipe
import cats.syntax.show.toShowOps
import com.advancedtelematic.director.daemon.FileCacheDaemon.Tick
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{FileCacheRequest}
import com.advancedtelematic.director.data.FileCacheRequestStatus.{ERROR, SUCCESS}
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport, FileCacheRequestRepositorySupport}
import io.circe.Json
import io.circe.syntax._
import scala.async.Async._
import scala.concurrent.Future
import scala.concurrent.duration._
import slick.driver.MySQLDriver.api._

object FileCacheDaemon {
  case object Tick

  def props(implicit db: Database):Props = Props(new FileCacheDaemon)
}

class FileCacheDaemon(implicit val db: Database) extends Actor
    with ActorLogging
    with FileCacheRequestRepositorySupport {

  implicit val ec = context.dispatcher

  override def preStart(): Unit = {
    self ! Tick
  }

  private val worker = {
    context.system.actorOf(FileCacheWorker.props)
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
  def props(implicit db: Database): Props = Props(new FileCacheWorker)
}

class FileCacheWorker(implicit val db: Database) extends Actor
    with ActorLogging
    with AdminRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport {

  implicit val ec = context.dispatcher

  def processFileCacheRequest(fcr: FileCacheRequest): Future[Unit] = async {
    //val repo = await(repoNameRepository.getRepo(namespace))

    val namespace = fcr.namespace
    val device = fcr.device
    val version = fcr.version

    val targets = await(db.run(adminRepository.fetchTargetVersion(namespace, device, version)))


    val targetsJson = targets.asJson
    val snapshotJson = Json.fromString("NOT IMPLEMENTED YET")

//    val targetsObject = ???
  //  val targetsJson = await(tuf.sign(repo, Role.TARGETS, targetsObject))
    //val snapshot = SnapshotRole(expires = ???, meta = Map("root.json" -> ???, "targets.json" -> FileInfo(Map("sha256" -> hashOfTargets), targetsJson.length)))
    //val snapshotJson = await(tuf.sign(repo, Role.SNAPSHOT, snapshot))

    await(fileCacheRepository.storeTargets(device, version, targetsJson))
    await(fileCacheRepository.storeSnapshot(device, version, snapshotJson))

    ()
  }

  override def receive: Receive = {
    case fcr: FileCacheRequest =>
      log.info("Received file cache request for {} version: {}", fcr.device.show, fcr.version)

      processFileCacheRequest(fcr).flatMap{ res =>
        fileCacheRequestRepository.updateRequest(fcr.copy(status = SUCCESS)).map(_ => Success(res))
      }.recoverWith {
        case ex =>
          log.error("File cache failed: {}", ex.getMessage)
          fileCacheRequestRepository.updateRequest(fcr.copy(status = ERROR)).map(_ => Failure(ex))
      }.pipeTo(sender)
  }
}
