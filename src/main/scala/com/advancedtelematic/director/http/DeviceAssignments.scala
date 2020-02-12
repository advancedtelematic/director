package com.advancedtelematic.director.http

import java.time.Instant

import akka.http.scaladsl.util.FastFuture
import cats.implicits._
import com.advancedtelematic.director.data.AdminDataType.QueueResponse
import com.advancedtelematic.director.data.DbDataType.Assignment
import com.advancedtelematic.director.data.UptaneDataType.{TargetImage, _}
import com.advancedtelematic.director.db._
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateCanceled, DeviceUpdateEvent}
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceAssignments(implicit val db: Database, val ec: ExecutionContext) extends EcuRepositorySupport
  with HardwareUpdateRepositorySupport with AssignmentsRepositorySupport with EcuTargetsRepositorySupport {

  private val _log = LoggerFactory.getLogger(this.getClass)

  import scala.async.Async._

  def findDeviceAssignments(ns: Namespace, deviceId: DeviceId): Future[Vector[QueueResponse]] = async {

    val correlationIdToAssignments = await(assignmentsRepository.findBy(deviceId)).groupBy(_.correlationId)

    val deviceQueues =
      correlationIdToAssignments.map { case (correlationId, assignments) =>
        val images = assignments.map { assignment =>
          ecuTargetsRepository.find(ns, assignment.ecuTargetId).map { target =>
            assignment.ecuId -> TargetImage(Image(target.filename, FileInfo(Hashes(target.sha256), target.length)), target.uri)
          }
        }.toList.sequence

        val queue = images.map(_.toMap).map { images =>
          val inFlight = correlationIdToAssignments.get(correlationId).exists(_.exists(_.inFlight))
          QueueResponse(correlationId, images, inFlight = inFlight)
        }

        queue
      }

    await(Future.sequence(deviceQueues)).toVector
  }

  def findAffectedDevices(ns: Namespace, deviceIds: Seq[DeviceId], mtuId: UpdateId): Future[Seq[DeviceId]] = {
    findAffectedEcus(ns, deviceIds, mtuId).map { _.map(_._1.deviceId) }
  }

  private def findAffectedEcus(ns: Namespace, devices: Seq[DeviceId], mtuId: UpdateId) = async {
    val hardwareUpdates = await(hardwareUpdateRepository.findBy(ns, mtuId))

    await(ecuRepository.findFor(devices.toSet, hardwareUpdates.keys.toSet)).flatMap { ecu =>
      val hwUpdate = hardwareUpdates(ecu.hardwareId)

      // !ecu.installedTarget.contains(hwUpdate.toTarget) // TODO: Should not be affected if device already has update
      if (hwUpdate.fromTarget.isEmpty || ecu.installedTarget.contains(hwUpdate.fromTarget.get)) {
        if(ecu.installedTarget.contains(hwUpdate.toTarget)) {
          _log.debug("not affected")
          None
        } else {
          _log.info(s"AFFECTED: ${hwUpdate} ${ecu.installedTarget}")
          Some(ecu -> hwUpdate.toTarget)
        }
      } else {
        _log.debug(s"ecu ${ecu.ecuSerial} not affected by $mtuId")
        None
      }
    }
  }

  def createForDevice(ns: Namespace, correlationId: CorrelationId, deviceId: DeviceId, mtuId: UpdateId): Future[Assignment] = {
    createForDevices(ns, correlationId, List(deviceId), mtuId).map(_.head)
  }

  def createForDevices(ns: Namespace, correlationId: CorrelationId, devices: Seq[DeviceId], mtuId: UpdateId): Future[Seq[Assignment]] = async {
    val ecus = await(findAffectedEcus(ns, devices, mtuId))

    if(ecus.isEmpty)
      throw Errors.NoDevicesAffected

    val assignments = ecus.foldLeft(List.empty[Assignment]) { case (acc, (ecu, toTargetId)) =>
      Assignment(ns, ecu.deviceId, ecu.ecuSerial, toTargetId, correlationId, inFlight = false) :: acc
    }

    if(await(assignmentsRepository.existsFor(assignments.map(_.ecuId).toSet)))
      throw Errors.AssignmentExists

    await(assignmentsRepository.persistMany(assignments))

    assignments
  }

  def cancel(namespace: Namespace, devices: Seq[DeviceId])(implicit messageBusPublisher: MessageBusPublisher): Future[Seq[Assignment]] = {
    assignmentsRepository.processCancellation(namespace, devices).flatMap { canceledAssignments =>
      Future.traverse(canceledAssignments) { canceledAssignment =>
        val ev: DeviceUpdateEvent =
          DeviceUpdateCanceled(namespace, Instant.now, canceledAssignment.correlationId, canceledAssignment.deviceId)
        messageBusPublisher.publish(ev).map(_ => canceledAssignment)
      }
    }
  }

}
