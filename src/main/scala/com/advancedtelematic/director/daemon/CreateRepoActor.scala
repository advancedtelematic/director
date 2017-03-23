package com.advancedtelematic.director.daemon

import akka.actor.{Actor, Props}
import com.advancedtelematic.director.daemon.CreateRepoActor.RepoCreated
import com.advancedtelematic.director.http.RootFileDownloadActor
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import com.advancedtelematic.libtuf.data.TufDataType.RepoId

import scala.concurrent.Future
import slick.driver.MySQLDriver.api._
import akka.pattern.pipe
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated


object CreateRepoActor {
  sealed trait Msg
  case class RepoCreated(namespace: Namespace, repoId: RepoId) extends Msg

  def props(keyserverClient: KeyserverClient)(implicit db: Database) =
    Props(new CreateRepoActor(keyserverClient))
}

class CreateRepoActor(keyserverClient: KeyserverClient)(implicit db: Database)
  extends Actor {

  import context.dispatcher

  def createRepo(userCreated: UserCreated): Future[RepoCreated] = {
    val repoId = RepoId.generate
    val namespace = Namespace(userCreated.id)
    keyserverClient.createRoot(repoId).map(_ => RepoCreated(namespace, repoId))
  }

  override def receive: Receive = {
    case uc: UserCreated =>
      createRepo(uc).pipeTo(self)

    case RepoCreated(ns, repoId) =>
      context.actorOf(RootFileDownloadActor.props(ns, keyserverClient, repoId))
  }
}
