package com.advancedtelematic.director.http

import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.actor.{Actor, ActorLogging, Props}
import com.advancedtelematic.director.db.RootFilesRepositorySupport
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, SignedPayload}
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import com.advancedtelematic.libtuf.data.TufCodecs._
import io.circe.Json
import io.circe.syntax._

import scala.concurrent.duration._
import slick.driver.MySQLDriver.api._


object RootFileDownloadActor {
  case object Tick
  def props(namespace: Namespace, tufClient: KeyserverClient, repoId: RepoId)(implicit db: Database): Props
    = Props(new RootFileDownloadActor(namespace, tufClient, repoId))
}

class RootFileDownloadActor(namespace: Namespace, tufClient: KeyserverClient, repoId: RepoId)
                           (implicit val db: Database) extends Actor
  with RootFilesRepositorySupport
  with ActorLogging {

  import RootFileDownloadActor.Tick

  implicit val ec = context.dispatcher

  var remaining: Int = 50

  override def preStart(): Unit = {
    self ! Tick
  }

  override def receive: Receive = {
    case rootFile: SignedPayload[Json @unchecked] =>
      rootFilesRepository.persistNamespaceRootFile(namespace, rootFile.asJson, repoId)
      context stop self

    // TODO: Check for HttpLock and if not, die right away
    case Failure(ex) =>
      if (remaining > 0) {
        remaining = remaining - 1
        log.info(s"root.json not generated yet for $namespace, $remaining attempts ({})", ex.getMessage)
        context.system.scheduler.scheduleOnce(3.seconds, self, Tick)
      } else {
        log.error(s"root.json could not be generated for $namespace", ec)
        context stop self
      }

    case Tick =>
      log.info("Tick")

      tufClient.fetchRootRole(repoId).pipeTo(self)
  }
}
