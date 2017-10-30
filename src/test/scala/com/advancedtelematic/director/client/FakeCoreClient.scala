package com.advancedtelematic.director.client

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.DeviceRequest.OperationResult
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future

object FakeCoreClient extends CoreClient {

  private val reports: ConcurrentHashMap[UpdateId, Seq[OperationResult]] = new ConcurrentHashMap()

  override def updateReport(namespace: Namespace, device: DeviceId, update: UpdateId, operations: Seq[OperationResult]): Future[Unit] =
    FastFuture.successful(reports.put(update, operations))

  def getReport(update: UpdateId): Seq[OperationResult] =
    reports.get(update)

}
