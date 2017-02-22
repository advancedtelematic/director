package com.advancedtelematic.director.http

import com.advancedtelematic.director.data.DataType.Namespace
import com.advancedtelematic.director.db.{RepoNameRepositorySupport, RootFilesRepositorySupport}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.keyserver.KeyserverClient

import io.circe.syntax._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

import slick.driver.MySQLDriver.api._

object RegisterNamespace extends RepoNameRepositorySupport with RootFilesRepositorySupport {
  def action(tufClient: KeyserverClient, namespace: Namespace)
            (implicit db: Database, ec: ExecutionContext): Future[RepoId] = async {
    val repoId = RepoId.generate

    await(tufClient.createRoot(repoId))

    val rootFile = await(tufClient.fetchRootRole(repoId))

    val dbAct = rootFilesRepository.persistAction(namespace, rootFile.asJson)
      .andThen(repoNameRepository.persistAction(namespace, repoId))

    await(db.run(dbAct.transactionally))

    repoId
  }
}
