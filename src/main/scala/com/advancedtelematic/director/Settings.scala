package com.advancedtelematic.director

import akka.event.Logging
import com.typesafe.config.ConfigFactory

trait Settings {
  import Util._

  private lazy val _config = ConfigFactory.load()

  val host = _config.getString("server.host")
  val port = _config.getInt("server.port")

  val tufUri = mkUri(_config, "keyserver.uri")

  val requestLogLevel = Logging.levelFor(_config.getString("requestLogLevel")).getOrElse(Logging.DebugLevel)
}
