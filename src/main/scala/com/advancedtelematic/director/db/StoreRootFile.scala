package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{Namespace, RepoName}
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import io.circe.Json

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

object StoreRootFile extends RepoNameRepositorySupport
    with RootFilesRepositorySupport {
  def action(namespace: Namespace, repoId: RepoId, rootFile: Json)
            (implicit db: Database, ec: ExecutionContext): Future[RepoName] = db.run {
    rootFilesRepository.persistAction(namespace, rootFile)
      .andThen(repoNameRepository.persistAction(namespace, repoId))
      .transactionally
  }
}
