package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.typesafe.config.{Config, ConfigFactory}
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.ExecutionContext
import scala.util.Try

object NamespaceDirectives {
  import akka.http.scaladsl.server.Directives._

  val NAMESPACE = "x-ats-namespace"

  def configNamespace(config: Config): Namespace = Namespace("default")

  lazy val fromHeader: Directive1[Option[Namespace]] =
    optionalHeaderValueByName(NAMESPACE).map(_.map(Namespace(_)))

  lazy val defaultNamespaceExtractor: Directive1[Namespace] = fromHeader.flatMap {
    case Some(ns) => provide(ns)
    case None => provide(configNamespace(ConfigFactory.load()))
  }
}

trait NamespaceDirectives extends RepoNameRepositorySupport {
  implicit val db: Database
  implicit val ec: ExecutionContext

  def withRepoId(ns: Namespace): Directive1[RepoId] =
    onSuccess(repoNameRepository.getRepo(ns))
}
