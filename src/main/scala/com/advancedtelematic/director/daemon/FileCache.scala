package com.advancedtelematic.director.daemon

import akka.actor.Status.{Failure, Success}
import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern.pipe
import cats.syntax.show.toShowOps
import com.advancedtelematic.director.daemon.FileCacheDaemon.Tick
import com.advancedtelematic.director.data.DataType.FileCacheRequest
import com.advancedtelematic.director.data.FileCacheRequestStatus.{ERROR, SUCCESS}
import com.advancedtelematic.director.data.Utility.ToCanonicalJsonOps
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport, FileCacheRequestRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.libtuf.crypt.Sha256Digest
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, MetaItem, RoleTypeToMetaPathOp, SnapshotRole, TargetsRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, RoleType}
import com.advancedtelematic.libtuf.repo_store.RoleKeyStoreClient
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.advancedtelematic.libats.codecs.AkkaCirce.refinedEncoder
import scala.async.Async._
import scala.concurrent.Future
import scala.concurrent.duration._
import slick.driver.MySQLDriver.api._

object FileCacheDaemon {
  case object Tick

  def props(tuf: RoleKeyStoreClient)(implicit db: Database):Props = Props(new FileCacheDaemon(tuf))
}

class FileCacheDaemon(tuf: RoleKeyStoreClient)(implicit val db: Database) extends Actor
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
  def props(tuf: RoleKeyStoreClient)(implicit db: Database): Props = Props(new FileCacheWorker(tuf))
}

class FileCacheWorker(tuf: RoleKeyStoreClient)(implicit val db: Database) extends Actor
    with ActorLogging
    with AdminRepositorySupport
    with FileCacheRepositorySupport
    with FileCacheRequestRepositorySupport
    with RepoNameRepositorySupport {

  implicit val ec = context.dispatcher

  def generateTargetFile(repoId: RepoId, fcr: FileCacheRequest): Future[Json] = async {
    val namespace = fcr.namespace
    val device = fcr.device
    val version = fcr.version

    val targets = await(adminRepository.fetchTargetVersion(fcr.namespace, fcr.device, fcr.version))

    val clientsTarget = targets.map { case (ecu_serial, image) =>
      val item = ClientTargetItem(image.fileinfo.hashes, image.fileinfo.length, Json.obj("ecuSerial" -> ecu_serial.asJson))
      image.filepath -> item
    }

    val targetsRole = TargetsRole(expires = Instant.now.plus(31, ChronoUnit.DAYS),
                                  targets = clientsTarget,
                                  version = fcr.version)
    await(tuf.sign(repoId, RoleType.TARGETS, targetsRole)).asJson
  }

  def generateSnapshotFile(repoId: RepoId, targetsJson: Json, version: Int): Future[Json] = async {
    val targetsFile = targetsJson.canonicalBytes
    val targetChecksum = Sha256Digest.digest(targetsFile)

    val metaMap = Map(RoleType.TARGETS.toMetaPath -> MetaItem(Map(targetChecksum.method -> targetChecksum.hash), targetsFile.length))

    val snapshotRole = SnapshotRole(meta = metaMap,
                                    expires = Instant.now.plus(31, ChronoUnit.DAYS),
                                    version = version)

    await(tuf.sign(repoId, RoleType.SNAPSHOT, snapshotRole)).asJson
  }

  def processFileCacheRequest(fcr: FileCacheRequest): Future[Unit] = async {
    val repo = await(repoNameRepository.getRepo(fcr.namespace))

    val targetsJson = await(generateTargetFile(repo, fcr))
    val snapshotJson = await(generateSnapshotFile(repo, targetsJson, fcr.version))

    // What about timestamp.json?


    val dbAct = fileCacheRepository.storeTargetsAction(fcr.device, fcr.version, targetsJson)
      .andThen(fileCacheRepository.storeSnapshotAction(fcr.device, fcr.version, snapshotJson))

    await(db.run(dbAct.transactionally))
  }

  override def receive: Receive = {
    case fcr: FileCacheRequest =>
      log.info("Received file cache request for {} version: {}", fcr.device.show, fcr.version)

      processFileCacheRequest(fcr)
        .map(Success)
        .flatMap { _ => fileCacheRequestRepository.updateRequest(fcr.copy(status = SUCCESS)) }
        .recoverWith {
          case ex =>
            log.error("File cache failed: {}", ex.getMessage)
            fileCacheRequestRepository.updateRequest(fcr.copy(status = ERROR)).map(_ => Failure(ex))
        }.pipeTo(sender)
  }
}
