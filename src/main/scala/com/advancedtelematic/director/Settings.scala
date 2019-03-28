package com.advancedtelematic.director

import com.advancedtelematic.libtuf.data.TufDataType.KeyType
import com.typesafe.config.ConfigFactory

import scala.util.Try

trait Settings {
  import Util._

  private lazy val _config = ConfigFactory.load()

  val host = _config.getString("server.host")
  val port = _config.getInt("server.port")

  val tufUri = mkUri(_config, "keyserver.uri")
  val tufBinaryUri = mkUri(_config, "tuf.binary.uri")

  val defaultKeyType: Try[KeyType] = {
    Try(_config.getString("daemon.defaultKeyType")).map { defaultKeyTypeName =>
      namedType[KeyType](defaultKeyTypeName)
    }
  }
}
