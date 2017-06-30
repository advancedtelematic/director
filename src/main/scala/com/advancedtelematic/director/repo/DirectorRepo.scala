package com.advancedtelematic.director.repo

import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.keyserver.KeyserverClient

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api.Database

class DirectorRepo(keyserverClient: KeyserverClient)(implicit db: Database, ec: ExecutionContext) extends RepoNameRepositorySupport {
  def create(namespace: Namespace): Future[RepoId] = {
    val repoId = RepoId.generate
    keyserverClient.createRoot(repoId).flatMap{ _ =>
      repoNameRepository.persist(namespace, repoId)
    }.map(_ => repoId)
  }
}
