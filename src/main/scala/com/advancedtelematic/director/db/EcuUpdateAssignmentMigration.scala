package com.advancedtelematic.director.db

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.Done
import cats.syntax.either._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import org.slf4j.LoggerFactory
import slick.jdbc.GetResult
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


class EcuUpdateAssignmentMigration(implicit
  val db: Database,
  val mat: Materializer,
  val system: ActorSystem,
  val ec: ExecutionContext)
  extends DeviceUpdateAssignmentRepositorySupport
  with EcuUpdateAssignmentRepositorySupport {

  private val _log = LoggerFactory.getLogger(this.getClass)

  implicit val getResult: GetResult[(Namespace,DeviceUpdateTarget)] = pr => {
    val correlationId = pr.nextStringOption.map(
          CorrelationId.fromString(_).valueOr(s => throw new IllegalArgumentException(s)))
    val updateId = pr.nextStringOption.map(s => UpdateId(UUID.fromString(s)))
    val deviceId = implicitly[ColumnType[DeviceId]].getValue(pr.rs, 3)
    val version = implicitly[ColumnType[Int]].getValue(pr.rs, 4)
    val served = implicitly[ColumnType[Boolean]].getValue(pr.rs, 5)
    val namespace = implicitly[ColumnType[Namespace]].getValue(pr.rs, 6)
    (namespace, DeviceUpdateTarget(deviceId, correlationId, updateId, version, served))
  }

  val queryExistingAssignments = sql"""
    SELECT dt.correlation_id, dt.update_uuid, dt.device, dt.version, dt.served, e.namespace
    FROM device_update_targets dt
    INNER JOIN ecus e
    ON e.device = dt.device
  """.as[(Namespace, DeviceUpdateTarget)]

  def run: Future[Done] = {
    val source = db.stream(queryExistingAssignments)
    Source.fromPublisher(source).mapAsync(1) { case (namespace, oldAssignment) =>
      db.run {
        for {
          targets <- ecuUpdateAssignmentRepository.legacyFetchAction(
            namespace,
            oldAssignment.device,
            oldAssignment.targetVersion)
          _ <- deviceUpdateAssignmentRepository.persistAction(
            namespace,
            oldAssignment.device,
            oldAssignment.correlationId,
            oldAssignment.updateId,
            oldAssignment.targetVersion).asTry.flatMap {
              case Success(_) =>
                _log.debug(s"Migrated EcuUpdateAssignment: $oldAssignment $targets")
                ecuUpdateAssignmentRepository.persistAction(
                  namespace,
                  oldAssignment.device,
                  oldAssignment.targetVersion,
                  targets)
              case Failure(Errors.ConflictingTarget) => DBIO.successful(())
              case Failure(ex) => DBIO.failed(ex)
            }
          } yield ()
        }
      }
    .runWith(Sink.foreach { _ => () })
  }
}

