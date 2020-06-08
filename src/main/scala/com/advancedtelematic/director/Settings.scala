package com.advancedtelematic.director

import akka.event.Logging
import akka.http.scaladsl.model.Uri
import com.typesafe.config.ConfigFactory

trait Settings {
  private lazy val _config = ConfigFactory.load()

  val host = _config.getString("server.host")
  val port = _config.getInt("server.port")

  val tufUri = Uri(_config.getString("keyserver.uri"))

  val requestLogLevel = Logging.levelFor(_config.getString("requestLogLevel")).getOrElse(Logging.DebugLevel)
}
