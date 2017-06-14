package com.advancedtelematic.director.migration

import akka.Done
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.director.daemon.CreateRepoActor
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.director.db.Errors.MissingNamespaceRepo
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libtuf.keyserver.KeyserverHttpClient
import scala.concurrent.Future

object CreateUsersBoot extends BootApp
    with Settings
    with VersionInfo
    with DatabaseConfig
    with RepoNameRepositorySupport
    with BootMigrations {

  implicit val _db = db

  log.info("Starting migration for CreateUsers")

  require(args.length == 1, "need to pass a filepath to read namespaces from")
  val fp = args.head

  val tuf = new KeyserverHttpClient(tufUri)
  val createRepoListener = system.actorOf(CreateRepoActor.props(tuf), "create-repo-listener")

  def checkIfExists(ns: Namespace): Future[Boolean] =
    repoNameRepository.getRepo(ns).map(_ => true).recover {
      case MissingNamespaceRepo => false
    }

  def migrate(ns: Namespace): Future[Done] = {
    checkIfExists(ns).map {
      case true =>
        log.info(s"Namespace ${ns.get} already exists, skipping")
        Done
      case false =>
        log.info(s"Creating repo for ${ns.get}")
        createRepoListener ! UserCreated(ns.get)
        Done
    }
  }

  def migrateAll(namespaces: Seq[Namespace]): Future[Done] = {
    // this might be stupid, maybe should buffer them somehow
    Future.traverse(namespaces)(migrate).map(_ => Done)
  }

  def readNamespaces(fp: String): Seq[Namespace] = {
    scala.io.Source.fromFile(fp).mkString.lines.toSeq.map(Namespace)
  }

  val namespaces = readNamespaces(fp)

  migrateAll(namespaces).foreach { case Done =>
    log.info(s"Migration finished")
  }
}
