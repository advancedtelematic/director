package com.advancedtelematic.diff_service.daemon

import akka.Done
import cats.syntax.show._
import com.advancedtelematic.diff_service.data.DataType.DiffStatus
import com.advancedtelematic.diff_service.db.{BsDiffRepositorySupport, StaticDeltaRepositorySupport}
import com.advancedtelematic.diff_service.db.Errors._
import com.advancedtelematic.libats.messaging_datatype.Messages.{BsDiffGenerationFailed, DeltaGenerationFailed, GeneratedBsDiff, GeneratedDelta}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api.Database

class DiffListener (implicit db: Database, ec: ExecutionContext)
    extends BsDiffRepositorySupport
    with StaticDeltaRepositorySupport {

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def generatedDeltaAction(msg: GeneratedDelta): Future[Done] =
    staticDeltaRepository.persistInfo(msg.namespace, msg.id, msg.uri, msg.size, msg.checksum).map(_ => Done).recover {
      case ConflictingStaticDeltaInfo =>
        _log.error(s"The info for ${msg.id.show} already exists, ignoring message.")
        Done
      case MissingStaticDelta =>
        _log.error(s"The static delta request ${msg.id.show} was not created by us, so we ignore.")
        Done
    }

  def generatedBsDiffAction(msg: GeneratedBsDiff): Future[Done] =
    bsdiffRepository.persistInfo(msg.namespace, msg.id, msg.resultUri, msg.size, msg.checksum).map(_ => Done).recover {
      case ConflictingBsDiffInfo =>
        _log.error(s"The info for ${msg.id.show} already exists, ignoring message.")
        Done
      case MissingBsDiff =>
        _log.error(s"The bs diff request ${msg.id.show} was not created by director, will ignore.")
        Done
    }

  def deltaGenerationFailedAction(msg: DeltaGenerationFailed): Future[Done] = {
    _log.info(s"The static delta request ${msg.id.show} failed with error: ${msg.error.getOrElse("")}")
    staticDeltaRepository.setStatus(msg.namespace, msg.id, DiffStatus.FAILED).map(_ => Done ).recover {
      case MissingStaticDelta =>
        _log.error(s"The static delta request ${msg.id.show} was not created by us, so we ignore")
        Done
    }
  }

  def bsDiffGenerationFailedAction(msg: BsDiffGenerationFailed): Future[Done] = {
    _log.info(s"The bs diff request ${msg.id.show} failed with errror: ${msg.error.getOrElse("")}")
    bsdiffRepository.setStatus(msg.namespace, msg.id, DiffStatus.FAILED).map(_ => Done ).recover {
      case MissingStaticDelta =>
        _log.error(s"The bs diff ${msg.id.show} was not created by us, so we ignore")
        Done
    }
  }
}
