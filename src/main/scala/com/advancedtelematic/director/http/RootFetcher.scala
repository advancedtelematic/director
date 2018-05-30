package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Route
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.RootRole
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, SignedPayload}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import scala.concurrent.Future
import slick.jdbc.MySQLProfile.api.Database
import scala.concurrent.ExecutionContext


trait RootFetcher extends RepoNameRepositorySupport {

  def fetchRoot(namespace: Namespace): Route =
    completeWithRoot(namespace, keyserverClient.fetchRootRole)

  def fetchRoot(namespace: Namespace, version: Int): Route =
    completeWithRoot(namespace, keyserverClient.fetchRootRole(_, version))

  private def completeWithRoot(namespace: Namespace, fetch: RepoId => Future[SignedPayload[RootRole]]): Route = {
    val f = repoNameRepository.getRepo(namespace).flatMap { repo =>
      fetch(repo)
    }

    complete(f)
  }

  val keyserverClient: KeyserverClient
  implicit val db: Database
  implicit val ec: ExecutionContext

}
