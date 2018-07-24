package com.advancedtelematic.diff_service.client

import akka.Done
import akka.http.scaladsl.model.Uri
import com.advancedtelematic.diff_service.data.DataType.CreateDiffInfoRequest
import com.advancedtelematic.director.data.DataType.{DiffInfo, TargetUpdate}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{BsDiffRequestId, DeltaRequestId}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class FakeDiffServiceClient(binaryUri: Uri)(implicit ec: ExecutionContext) extends DiffServiceClient {

  val staticDeltaIds = new ConcurrentHashMap[(TargetUpdate, TargetUpdate), DeltaRequestId]
  val bsDiffIds = new ConcurrentHashMap[(TargetUpdate, TargetUpdate), BsDiffRequestId]

  val knownStaticDelta = new ConcurrentHashMap[DeltaRequestId, DiffInfo]
  val knownBsDiff = new ConcurrentHashMap[BsDiffRequestId, DiffInfo]

  override def createDiffInfo(namespace: Namespace, diffRequests: Seq[CreateDiffInfoRequest]): Future[Done] =
    Future.traverse(diffRequests) { diffRequest =>
      diffRequest.format match {
        case OSTREE =>
          val id = DeltaRequestId.generate
          Future{staticDeltaIds.put((diffRequest.from, diffRequest.to), id)}
        case BINARY =>
          val id = BsDiffRequestId.generate
          Future{bsDiffIds.put((diffRequest.from, diffRequest.to), id)}
      }
    }.map(_ => Done)

  override def findDiffInfo(namespace: Namespace, targetFormat: TargetFormat, from: TargetUpdate, to: TargetUpdate): Future[Option[DiffInfo]] =
    targetFormat match {
      case OSTREE => Future.fromTry {
        Try{
          staticDeltaIds.asScala((from, to))
        }
      }.flatMap { id =>
        Future.fromTry(Try(Some(knownStaticDelta.asScala(id))))
      }.recover {
        case _ => None
      }
      case BINARY => Future.fromTry {
        Try{
          bsDiffIds.asScala((from, to))
        }
      }.flatMap { id =>
        Future.fromTry(Try(Some(knownBsDiff.asScala(id))))
      }.recover {
        case _ => None
      }

    }

  def generate(targetFormat: TargetFormat, from: TargetUpdate, to: TargetUpdate, diff: DiffInfo): Unit = targetFormat match {
    case OSTREE =>
      val id = staticDeltaIds.asScala((from, to))
      registerStaticDelta(id, diff)
    case BINARY =>
      val id = bsDiffIds.asScala((from, to))
      registerBsDiff(id, diff)
  }

  def registerStaticDelta(id: DeltaRequestId, diff: DiffInfo): Unit = knownStaticDelta.put(id, diff)
  def registerBsDiff(id: BsDiffRequestId, diff: DiffInfo): Unit = knownBsDiff.put(id, diff)
}
