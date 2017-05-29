package com.advancedtelematic.director

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.director.manifest.SignatureVerification
import com.advancedtelematic.libtuf.keyserver.KeyserverHttpClient
import com.typesafe.config.{Config, ConfigFactory}
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.advancedtelematic.director.client.CoreHttpClient
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.http.{BootApp, HealthResource}
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libats.monitoring.MetricsSupport
import com.advancedtelematic.metrics.{AkkaHttpMetricsSink, InfluxDbMetricsReporter, InfluxDbMetricsReporterSettings, OsMetricSet}
import com.codahale.metrics.jvm.ThreadStatesGaugeSet
import io.circe.Decoder
import org.slf4j.LoggerFactory

trait Settings {
  private def mkUri(config: Config, key: String): Uri = {
    val uri = Uri(config.getString(key))
    if (!uri.isAbsolute) {
      throw new IllegalArgumentException(s"$key is not an absolute uri")
    }
    uri
  }

  lazy val config = ConfigFactory.load()

  val host = config.getString("server.host")
  val port = config.getInt("server.port")

  val tufUri = mkUri(config, "tuf.uri")
  val coreUri = mkUri(config, "core.uri")


  val metricsReporterSettings: Option[InfluxDbMetricsReporterSettings] = {
    import com.advancedtelematic.circe.config.finiteDurationDecoder

    implicit val metricsReporterDecoder: Decoder[Option[InfluxDbMetricsReporterSettings]] =
      Decoder.decodeBoolean.prepare(_.downField("reportMetrics")).flatMap { enabled =>
        if (enabled)
          io.circe.generic.semiauto.deriveDecoder[InfluxDbMetricsReporterSettings].map(Some.apply)
        else Decoder.const(None)
      }
    import com.advancedtelematic.circe.config.decodeConfigAccumulating
    decodeConfigAccumulating(config)(metricsReporterDecoder.prepare(_.downField("ats").downField("metricsReporter"))).fold ({ x =>
      val errorMessage = s"Invalid service configuration: ${x.show}"
      LoggerFactory.getLogger(this.getClass).error(errorMessage)
      throw new Throwable(errorMessage)
    }, identity)
  }

}

object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with DatabaseConfig
  with BootMigrations
  with MetricsSupport
  with DatabaseMetrics {

  implicit val _db = db

  log.info(s"Starting $version on http://$host:$port")

  val coreClient = new CoreHttpClient(coreUri)
  val tuf = new KeyserverHttpClient(tufUri)

  Security.addProvider(new BouncyCastleProvider())
  metricsReporterSettings.foreach{ x =>
    metricRegistry.register("jvm.thread", new ThreadStatesGaugeSet())
    metricRegistry.register("jvm.os", OsMetricSet)
    InfluxDbMetricsReporter.start(x, metricRegistry, AkkaHttpMetricsSink.apply(x))
  }
  val routes: Route =
    new HealthResource(Seq(DbHealthResource.HealthCheck(db)), versionMap).route ~
    (versionHeaders(version) & logResponseMetrics(projectName)) {
      new DirectorRoutes(SignatureVerification.verify, coreClient, tuf).routes
    }

  Http().bindAndHandle(routes, host, port)
}
