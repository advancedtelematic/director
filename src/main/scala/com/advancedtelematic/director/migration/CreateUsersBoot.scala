package com.advancedtelematic.director.migration

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.director.db.Errors.MissingNamespaceRepo
import com.advancedtelematic.director.http.RootFileDownloadActor
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.keyserver.KeyserverHttpClient
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object CreateUsersBoot extends BootApp
    with Settings
    with VersionInfo
    with DatabaseConfig
    with RepoNameRepositorySupport
    with BootMigrations {

  implicit val _db = db

  log.info("Starting migration for CreateUsers")

  val (dryRun: Boolean, fileOfNamespaces: String) = args match {
    case Array("--dryrun", fp) => (true, fp)
    case Array(fp) if fp != "--dryrun" => (false, fp)
    case _ =>
      log.error("wrong arguments passed should be of the form: [--dryrun] filename")
      sys.exit(1)
  }

  def readNamespaces(fp: String): Seq[Namespace] = {
    scala.io.Source.fromFile(fp).mkString.lines.toSeq.map(Namespace)
  }

  val namespaces = readNamespaces(fileOfNamespaces)

  val keyserverClient = new KeyserverHttpClient(tufUri)

  def checkIfExists(ns: Namespace): Future[Boolean] =
    repoNameRepository.getRepo(ns).map(_ => true).recover {
      case MissingNamespaceRepo => false
    }

  def migrate(ns: Namespace): Future[Done] = {
    checkIfExists(ns).flatMap {
      case true =>
        log.info(s"Namespace ${ns.get} already exists, skipping")
        FastFuture.successful(Done)
      case false =>
        log.info(s"Creating repo for ${ns.get}")
        if (dryRun) {
          log.info(s"[DRYRUN] createRepoListener ! UserCreated(${ns.get})")
          FastFuture.successful(Done)
        } else {
          val repoId = RepoId.generate
          keyserverClient.createRoot(repoId).map{_ =>
            system.actorOf(RootFileDownloadActor.props(ns, keyserverClient, repoId))
            Done
          }
        }
    }
  }

  def migrateAll(namespaces: Seq[Namespace]): Future[Done] = {
    val bufferSize = 5

    namespaces.grouped(bufferSize).toSeq.foldLeft(Future.successful(Done)) {
      (acc, group) => acc.flatMap(_ => Future.traverse(group)(migrate).map(_ => Done))
    }
  }

  Await.result(migrateAll(namespaces), Duration.Inf)
  log.info(s"Migration finished")
}
