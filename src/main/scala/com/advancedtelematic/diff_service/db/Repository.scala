package com.advancedtelematic.diff_service.db

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.diff_service.data.DataType.{BsDiff, BsDiffInfo, BsDiffQueryResponse, DiffStatus, StaticDelta, StaticDeltaInfo, StaticDeltaQueryResponse}
import com.advancedtelematic.diff_service.data.DataType.DiffStatus.DiffStatus
import com.advancedtelematic.diff_service.db.Errors._
import com.advancedtelematic.diff_service.db.SlickMappings._
import com.advancedtelematic.libats.data.DataType.{Checksum, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.{BsDiffRequestId, Commit, DeltaRequestId}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

trait BsDiffRepositorySupport {
  def bsdiffRepository(implicit db: Database, ec: ExecutionContext) = new BsDiffRepository()
}

trait StaticDeltaRepositorySupport {
  def staticDeltaRepository(implicit db: Database, ec: ExecutionContext) = new StaticDeltaRepository()
}

protected class BsDiffRepository()(implicit db: Database, ec: ExecutionContext) {

  def persist(namespace: Namespace, id: BsDiffRequestId, from: Uri, to: Uri, status: DiffStatus = DiffStatus.REQUESTED): Future[BsDiff] = db.run {
    val bsDiff = BsDiff(namespace, id, from, to, status)
    (Schema.bsDiffs += bsDiff)
      .handleIntegrityErrors(ConflictingBsDiff)
      .map(_ => bsDiff)
  }

  protected [db] def setStatusAction(namespace: Namespace, id: BsDiffRequestId, status: DiffStatus): DBIO[Unit] =
    Schema.bsDiffs
      .filter(_.namespace === namespace)
      .filter(_.id === id)
      .map(_.status)
      .update(status)
      .handleSingleUpdateError(MissingBsDiff)

  def setStatus(namespace: Namespace, id: BsDiffRequestId, status: DiffStatus): Future[Unit] = db.run(setStatusAction(namespace, id, status))

  def persistInfo(namespace: Namespace, id: BsDiffRequestId, uri: Uri, size: Long, checksum: Checksum): Future[BsDiffInfo] = db.run {
    val info = BsDiffInfo(id, checksum, size, uri)

    setStatusAction(namespace, id, DiffStatus.GENERATED).flatMap {_ =>
      (Schema.bsDiffInfos += info)
        .handleIntegrityErrors(ConflictingBsDiffInfo)
        .map(_ => info)
    }
  }

  def findId(namespace: Namespace, from: Uri, to: Uri): Future[BsDiffRequestId] = db.run {
    Schema.bsDiffs
      .filter(_.namespace === namespace)
      .filter(_.from === from)
      .filter(_.to === to)
      .map(_.id)
      .result
      .failIfNotSingle(MissingBsDiff)
  }

  def findInfo(namespace: Namespace, id: BsDiffRequestId): Future[BsDiffInfo] = db.run {
    Schema.bsDiffInfos
      .filter(_.id === id)
      .result
      .failIfNotSingle(MissingBsDiffInfo)
  }

  def findResponse(namespace: Namespace, from: Uri, to: Uri): Future[BsDiffQueryResponse] = db.run {
    Schema.bsDiffs
      .filter(_.namespace === namespace)
      .filter(_.from === from)
      .filter(_.to === to)
      .map(x => (x.id, x.status))
      .result
      .failIfNotSingle(MissingBsDiff)
      .flatMap { x =>
        val (id, status) = x
        Schema.bsDiffInfos
          .filter(_.id === id)
          .result
          .failIfMany
          .map(BsDiffQueryResponse(id, status, _))
    }
  }
}

protected class StaticDeltaRepository()(implicit db: Database, ec: ExecutionContext) {
  def persist(namespace: Namespace, id: DeltaRequestId, from: Commit, to: Commit, status: DiffStatus = DiffStatus.REQUESTED): Future[StaticDelta] = db.run {
    val static = StaticDelta(namespace, id, from, to, status)
    (Schema.staticDeltas += static)
      .handleIntegrityErrors(ConflictingStaticDelta)
      .map(_ => static)
  }

  protected [db] def setStatusAction(namespace: Namespace, id: DeltaRequestId, status: DiffStatus): DBIO[Unit] =
    Schema.staticDeltas
      .filter(_.namespace === namespace)
      .filter(_.id === id)
      .map(_.status)
      .update(status)
      .handleSingleUpdateError(MissingStaticDelta)

  def setStatus(namespace: Namespace, id: DeltaRequestId, status: DiffStatus): Future[Unit] = db.run(setStatusAction(namespace, id, status))

  def persistInfo(namespace: Namespace, id: DeltaRequestId, uri: Uri, size: Long, checksum: Checksum): Future[StaticDeltaInfo] = db.run {
    val info = StaticDeltaInfo(id, checksum, size, uri)

    setStatusAction(namespace, id, DiffStatus.GENERATED).flatMap {_ =>
      (Schema.staticDeltaInfos += info)
        .handleIntegrityErrors(ConflictingStaticDeltaInfo)
        .map(_ => info)
    }
  }

  def findId(namespace: Namespace, from: Commit, to: Commit): Future[DeltaRequestId] = db.run {
    Schema.staticDeltas
      .filter(_.namespace === namespace)
      .filter(_.from === from)
      .filter(_.to === to)
      .map(_.id)
      .result
      .failIfNotSingle(MissingStaticDelta)
  }

  def findInfo(namespace: Namespace, id: DeltaRequestId): Future[StaticDeltaInfo] = db.run {
    Schema.staticDeltaInfos
      .filter(_.id === id)
      .result
      .failIfNotSingle(MissingStaticDeltaInfo)
  }

  def findResponse(namespace: Namespace, from: Commit, to: Commit): Future[StaticDeltaQueryResponse] = db.run {
    Schema.staticDeltas
      .filter(_.namespace === namespace)
      .filter(_.from === from)
      .filter(_.to === to)
      .map(x => (x.id, x.status))
      .result
      .failIfNotSingle(MissingStaticDelta)
      .flatMap { x =>
        val (id, status) = x
        Schema.staticDeltaInfos
          .filter(_.id === id)
          .result
          .failIfMany
          .map(StaticDeltaQueryResponse(id, status, _))
    }
  }
}
