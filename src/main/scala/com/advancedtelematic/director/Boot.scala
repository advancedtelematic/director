package com.advancedtelematic.director


import java.security.Security

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.monitoring.ServiceHealthCheck
import com.advancedtelematic.libats.http.tracing.Tracing
import com.advancedtelematic.libats.http.tracing.Tracing.ServerRequestTracing
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.slick.db.{CheckMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverHttpClient
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport
import com.advancedtelematic.metrics.{AkkaHttpConnectionMetrics, AkkaHttpRequestMetrics, MetricsSupport}
import org.bouncycastle.jce.provider.BouncyCastleProvider


object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with DatabaseConfig
  with MetricsSupport
  with DatabaseMetrics
  with AkkaHttpRequestMetrics
  with AkkaHttpConnectionMetrics
  with PrometheusMetricsSupport
  with CheckMigrations {

  implicit val _db = db

  log.info(s"Starting $version on http://$host:$port")

  lazy val tracing = Tracing.fromConfig(config, projectName)

  def keyserverClient(implicit tracing: ServerRequestTracing) = KeyserverHttpClient(tufUri)
  implicit val msgPublisher = MessageBus.publisher(system, config)

  Security.addProvider(new BouncyCastleProvider())

  val routes: Route =
    DbHealthResource(versionMap, dependencies = Seq(new ServiceHealthCheck(tufUri))).route ~
    (logRequestResult("directorv2-request-result" -> requestLogLevel) & versionHeaders(version) & requestMetrics(metricRegistry) & logResponseMetrics(projectName) & tracing.traceRequests) { implicit requestTracing =>
      prometheusMetricsRoutes ~
        new DirectorRoutes(keyserverClient).routes
    }

  Http().bindAndHandle(withConnectionMetrics(routes, metricRegistry), host, port)
}
