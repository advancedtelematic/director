package com.advancedtelematic.diff_service.client


import akka.http.scaladsl.model.Uri
import com.advancedtelematic.diff_service.data.DataType.CreateDiffInfoRequest
import com.advancedtelematic.director.data.DataType.{DiffInfo, TargetUpdate}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{BsDiffRequestId, DeltaRequestId}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._
import java.util.concurrent.ConcurrentHashMap

import akka.Done
import akka.http.scaladsl.util.FastFuture
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class FakeDiffServiceClient(binaryUri: Uri)(implicit ec: ExecutionContext) extends DiffServiceClient {

  type FromToUpdateKey = (String, String)

  implicit private class TargetTupleToUpdateKey(value: (TargetUpdate, TargetUpdate)) {
    def key: FromToUpdateKey = value match {
      case (from, to) => (s"${from.target.value}-${from.checksum.hash.value}", s"${to.target.value}-${to.checksum.hash.value}")
    }
  }

  private val staticDeltaIds = new ConcurrentHashMap[FromToUpdateKey, DeltaRequestId]
  private val bsDiffIds = new ConcurrentHashMap[FromToUpdateKey, BsDiffRequestId]

  private val knownStaticDelta = new ConcurrentHashMap[DeltaRequestId, DiffInfo]
  private val knownBsDiff = new ConcurrentHashMap[BsDiffRequestId, DiffInfo]

  private val _log = LoggerFactory.getLogger(this.getClass)

  override def createDiffInfo(namespace: Namespace, diffRequests: Seq[CreateDiffInfoRequest]): Future[Done] = {
    diffRequests.foreach { diffRequest =>
      diffRequest.format match {
        case OSTREE =>
          val id = DeltaRequestId.generate
          staticDeltaIds.put((diffRequest.from, diffRequest.to).key, id)
        case BINARY =>
          val id = BsDiffRequestId.generate
          bsDiffIds.put((diffRequest.from, diffRequest.to).key, id)
      }
    }

    FastFuture.successful(Done)
  }

  override def findDiffInfo(namespace: Namespace, targetFormat: TargetFormat, from: TargetUpdate, to: TargetUpdate): Future[Option[DiffInfo]] = {
    val maybeDiffInfo = targetFormat match {
      case OSTREE =>
        staticDeltaIds.asScala.get((from, to).key).flatMap(reqId => knownStaticDelta.asScala.get(reqId))
      case BINARY =>
        bsDiffIds.asScala.get((from, to).key).flatMap(reqId => knownBsDiff.asScala.get(reqId))
    }

    maybeDiffInfo match {
      case None =>
        _log.debug(s"Could not find ($from, $to) in known diffs")
        FastFuture.successful(None)
      case v@Some(_) =>
        FastFuture.successful(v)
    }
  }


  def generate(targetFormat: TargetFormat, from: TargetUpdate, to: TargetUpdate, diff: DiffInfo): Unit = targetFormat match {
    case OSTREE =>
      val id = staticDeltaIds.asScala((from, to).key)
      registerStaticDelta(id, diff)
    case BINARY =>
      val id = bsDiffIds.asScala((from, to).key)
      registerBsDiff(id, diff)
  }

  def registerStaticDelta(id: DeltaRequestId, diff: DiffInfo): Unit = knownStaticDelta.put(id, diff)
  def registerBsDiff(id: BsDiffRequestId, diff: DiffInfo): Unit = knownBsDiff.put(id, diff)
}
