package com.advancedtelematic.director.db

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import com.advancedtelematic.director.util.NamespaceTag.NamespaceTag
import com.advancedtelematic.director.util.RouteResourceSpec
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.Future

trait FileCacheDB extends FileCacheRequestRepositorySupport {
  self: RouteResourceSpec =>

  def makeFilesExpire(device: DeviceId): Future[Done] = db.run {
    Schema.fileCache
      .filter(_.device === device)
      .map(_.expires)
      .update(Instant.now.minus(10, ChronoUnit.DAYS))
  }.map(_ => Done)

  def generateAllPendingFiles(deviceId: Option[DeviceId] = None)(implicit ns: NamespaceTag): Future[Unit] = for {
    allPending <- fileCacheRequestRepository.findPending(limit = Int.MaxValue)
    devicePending = allPending.filter(p => deviceId.isEmpty || deviceId.contains(p.device)).filter(p => p.namespace == ns.get)
    _ <- Future.traverse(devicePending){fcr => rolesGeneration.processFileCacheRequest(fcr)}
  } yield ()
}
