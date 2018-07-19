package com.advancedtelematic.diff_service.client

import akka.Done
import akka.http.scaladsl.model.Uri
import com.advancedtelematic.diff_service.data.DataType.CreateDiffInfoRequest
import com.advancedtelematic.diff_service.db.{BsDiffRepositorySupport, StaticDeltaRepositorySupport}
import com.advancedtelematic.diff_service.db.Errors._
import com.advancedtelematic.director.data.DataType.TargetUpdate
import com.advancedtelematic.libats.data.DataType.{Checksum, Namespace}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.Messages.{BsDiffRequest, DeltaRequest}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._
import java.net.URI
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api.Database

import com.advancedtelematic.director.data.DataType.DiffInfo

trait DiffServiceClient {
  def createDiffInfo(namespace: Namespace, diffRequests: Seq[CreateDiffInfoRequest]): Future[Done]

  def findDiffInfo(namespace: Namespace, targetFormat: TargetFormat, from: TargetUpdate, to: TargetUpdate): Future[Option[DiffInfo]]
}

object DiffServiceDirectorClient
{
  import com.advancedtelematic.libats.data.RefinedUtils._
  import com.advancedtelematic.libats.messaging_datatype.DataType.{Commit, ValidCommit}

  def convertChecksumToCommit(checksum: Checksum): Commit = checksum.hash.value.refineTry[ValidCommit].get

  def convertToCommit(targetUpdate: TargetUpdate): Commit = convertChecksumToCommit(targetUpdate.checksum)

  def convertToBinaryUri(binaryUri: Uri, targetUpdate: TargetUpdate): Uri =
    binaryUri.withPath(binaryUri.path / targetUpdate.target.value)
}

class DiffServiceDirectorClient(binaryUri: Uri)
                               (implicit db: Database, ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
    extends DiffServiceClient
    with BsDiffRepositorySupport
    with StaticDeltaRepositorySupport
{
  import com.advancedtelematic.libats.messaging_datatype.DataType.{BsDiffRequestId, DeltaRequestId}

  import DiffServiceDirectorClient._

  override def createDiffInfo(namespace: Namespace, diffRequests: Seq[CreateDiffInfoRequest]): Future[Done] = {
    Future.traverse(diffRequests) { diffRequest =>
      diffRequest.format match {
        // these are quite similar, abstract more?
        case OSTREE =>
          val id = DeltaRequestId.generate
          val from = convertToCommit(diffRequest.from)
          val to = convertToCommit(diffRequest.to)
          messageBusPublisher.publish(DeltaRequest(id, namespace, from, to)).flatMap { _ =>
            staticDeltaRepository.persist(namespace, id, from, to)
          }
        case BINARY =>
          val id = BsDiffRequestId.generate
          val from = convertToBinaryUri(binaryUri, diffRequest.from)
          val to = convertToBinaryUri(binaryUri, diffRequest.to)
          def toURI(uri: Uri): URI = URI.create(uri.toString)
          messageBusPublisher.publish(BsDiffRequest(id, namespace, toURI(from), toURI(to))).flatMap { _ =>
            bsdiffRepository.persist(namespace, id, from, to)
          }
      }
    }.map(_ => Done)
  }

  override def findDiffInfo(namespace: Namespace, targetFormat: TargetFormat, from: TargetUpdate, to: TargetUpdate): Future[Option[DiffInfo]] =
    targetFormat match {
      case OSTREE =>
        val fromCommit = convertToCommit(from)
        val toCommit   = convertToCommit(to)
        staticDeltaRepository.findId(namespace, fromCommit, toCommit).flatMap { id =>
          staticDeltaRepository.findInfo(namespace, id).map{ staticDeltaInfo =>
            Some(DiffInfo(staticDeltaInfo.checksum, staticDeltaInfo.size, staticDeltaInfo.resultUri))
          }
        }.recover {
          case MissingStaticDelta => None
          case MissingStaticDeltaInfo => None
        }
      case BINARY =>
        val fromUri = convertToBinaryUri(binaryUri, from)
        val toUri   = convertToBinaryUri(binaryUri, to)
        bsdiffRepository.findId(namespace, fromUri, toUri).flatMap { id =>
          bsdiffRepository.findInfo(namespace, id).map{ bsDiffInfo =>
            Some(DiffInfo(bsDiffInfo.checksum, bsDiffInfo.size, bsDiffInfo.resultUri))
          }
        }.recover {
          case MissingBsDiff => None
          case MissingBsDiffInfo => None
        }
    }
}
