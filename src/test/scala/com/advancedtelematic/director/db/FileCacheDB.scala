package com.advancedtelematic.director.db

import akka.Done
import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

import SlickMapping._

trait FileCacheDB {
  def makeFilesExpire(device: DeviceId)
                     (implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    Schema.fileCache
      .filter(_.device === device)
      .map(_.expires)
      .update(Instant.now.minus(10, ChronoUnit.DAYS))
  }.map(_ => Done)

  def pretendToGenerate()(implicit db: Database, ec: ExecutionContext): Future[Done] = db.run {
    for {
      fcrs <- Schema.fileCacheRequest
        .filter(_.status === FileCacheRequestStatus.PENDING)
        .result
      _ <- Schema.fileCacheRequest
        .filter(_.device.inSet(fcrs.map(_.device).toSet))
        .filter(_.timestampVersion.inSet(fcrs.map(_.timestampVersion).toSet))
        .map(_.status)
        .update(FileCacheRequestStatus.SUCCESS)
    } yield Done
  }
}
