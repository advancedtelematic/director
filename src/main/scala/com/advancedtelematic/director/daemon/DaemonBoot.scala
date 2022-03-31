package com.advancedtelematic.director.daemon


import akka.actor.Scheduler
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.messaging.{BusListenerMetrics, MessageListenerSupport, MetricsBusMonitor}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeleteDeviceRequest
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.DbHealthResource
import com.advancedtelematic.libtuf_server.data.Messages.TufTargetAdded
import com.advancedtelematic.metrics.MetricsSupport
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport

object DaemonBoot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with BootMigrations
  with DatabaseConfig
  with MetricsSupport
  with MessageListenerSupport
  with PrometheusMetricsSupport {

  implicit val _db = db
  implicit val scheduler: Scheduler = system.scheduler

  import com.advancedtelematic.libats.http.VersionDirectives._

  val tufTargetAddedListener = startListener[TufTargetAdded](new TufTargetAddedListener, new MetricsBusMonitor(metricRegistry, "director-v2-tuf-target-added"))

  startListener[DeleteDeviceRequest](new DeleteDeviceRequestListener, new MetricsBusMonitor(metricRegistry, "director-v2-delete-device-request"))

  val routes = versionHeaders(version) {
    prometheusMetricsRoutes ~
      DbHealthResource(versionMap, healthMetrics = Seq(new BusListenerMetrics(metricRegistry))).route
  }

  Http().bindAndHandle(routes, host, port)
}
