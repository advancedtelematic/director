package com.advancedtelematic.director.http

import akka.actor.ActorSystem
import akka.actor.Status.{Failure, Success}
import akka.pattern.pipe
import akka.actor.{Actor, ActorLogging, Props}
import com.advancedtelematic.director.data.DataType.Namespace
import com.advancedtelematic.director.db.StoreRootFile
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.keyserver.KeyserverClient

import io.circe.syntax._
import io.circe.Json

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import slick.driver.MySQLDriver.api._

object RegisterNamespace {
  def action(tufClient: KeyserverClient, namespace: Namespace)
            (implicit db: Database, ec: ExecutionContext, system: ActorSystem): Future[RepoId] = async {
    val repoId = RepoId.generate

    await(tufClient.createRoot(repoId))

    system.actorOf(RootFileListener.props(namespace, tufClient, repoId))

    repoId
  }
}

object RootFileListener {
  case object Tick
  def props(namespace: Namespace, tufClient: KeyserverClient, repoId: RepoId)(implicit db: Database): Props
    = Props(new RootFileListener(namespace, tufClient, repoId))
}

class RootFileListener(namespace: Namespace, tufClient: KeyserverClient, repoId: RepoId)(implicit val db: Database) extends Actor
    with ActorLogging {
  import RootFileListener.Tick

  implicit val ec = context.dispatcher

  var remaing: Int = 50

  override def preStart(): Unit = {
    self ! Tick
  }

  override def receive: Receive = {
    case Success(rootFile : Json) =>
      StoreRootFile.action(namespace, repoId, rootFile)
      context stop self

    case Failure(ex) =>
      if (remaing > 0) {
        remaing = remaing - 1
        log.info(s"root.json not generated yet for $namespace, $remaing atempts", ex.getMessage)
        context.system.scheduler.scheduleOnce(3.seconds, self, Tick)
      } else {
        log.error(s"root.json could not be generated for $namespace")
        context stop self
      }

    case Tick =>
      log.info("Tick")
      tufClient.fetchRootRole(repoId).map(x => Success(x.asJson)).recover {
        case ex =>
          Failure(ex)
      }.pipeTo(self)
  }
}
