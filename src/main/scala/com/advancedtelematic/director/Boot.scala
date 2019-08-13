package com.advancedtelematic.director


import java.security.Security

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.monitoring.{MetricsSupport, ServiceHealthCheck}
import com.advancedtelematic.libats.http.tracing.Tracing
import com.advancedtelematic.libats.http.tracing.Tracing.RequestTracing
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.slick.db.{AsyncMigrations, BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf.data.TufDataType.KeyType
import com.advancedtelematic.libtuf_server.keyserver.KeyserverHttpClient
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport
import com.advancedtelematic.metrics.{AkkaHttpRequestMetrics, InfluxdbMetricsReporterSupport}
import com.typesafe.config.{Config, ConfigFactory}
import org.bouncycastle.jce.provider.BouncyCastleProvider

import scala.util.Try

object Util {
  def namedType[T](name: String): T = {
    val ru = scala.reflect.runtime.universe
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val objectSymbol = mirror.staticModule(name)
    val mm = mirror.reflectModule(objectSymbol)
    mm.instance.asInstanceOf[T]
  }

  def mkUri(config: Config, key: String): Uri = {
    val uri = Uri(config.getString(key))
    if (!uri.isAbsolute) {
      throw new IllegalArgumentException(s"$key is not an absolute uri")
    }
    uri
  }
}



object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with DatabaseConfig
  with MetricsSupport
  with DatabaseMetrics
  with InfluxdbMetricsReporterSupport
  with AkkaHttpRequestMetrics
  with PrometheusMetricsSupport
  with BootMigrations {

  implicit val _db = db

  log.info(s"Starting $version on http://$host:$port")

  lazy val tracing = Tracing.fromConfig(config, projectName)

  def keyserverClient(implicit tracing: RequestTracing) = KeyserverHttpClient(tufUri)
  implicit val msgPublisher = MessageBus.publisher(system, config)

  Security.addProvider(new BouncyCastleProvider())

  val routes: Route =
    DbHealthResource(versionMap, dependencies = Seq(new ServiceHealthCheck(tufUri))).route ~
    (versionHeaders(version) & requestMetrics(metricRegistry) & logResponseMetrics(projectName) & tracing.traceRequests) { implicit requestTracing: RequestTracing =>
      prometheusMetricsRoutes ~
        new DirectorRoutes(keyserverClient).routes
    }

  Http().bindAndHandle(routes, host, port)
}
