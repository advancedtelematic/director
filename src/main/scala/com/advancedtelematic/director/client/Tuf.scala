package com.advancedtelematic.director.client

import com.advancedtelematic.director.data.Role.Role
import io.circe.Json
import org.genivi.sota.data.Uuid
import scala.concurrent.Future

trait Tuf {
  def initRepo(uuid: Uuid): Future[Unit]

  def sign[T](uuid: Uuid, role: Role, value: Json): Future[Json]
}
