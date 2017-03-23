package com.advancedtelematic.director.http

import akka.http.scaladsl.server.Directive1
import com.advancedtelematic.libats.data.Namespace
import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Try

object NamespaceDirectives {
  import akka.http.scaladsl.server.Directives._

  val NAMESPACE = "x-ats-namespace"

  def configNamespace(config: Config): Namespace = {
    Namespace( Try(config.getString("core.defaultNs")).getOrElse("default"))
  }

  lazy val fromHeader: Directive1[Option[Namespace]] =
    optionalHeaderValueByName(NAMESPACE).map(_.map(Namespace(_)))

  lazy val defaultNamespaceExtractor: Directive1[Namespace] = fromHeader.flatMap {
    case Some(ns) => provide(ns)
    case None => provide(configNamespace(ConfigFactory.load()))
  }

}
