package com.advancedtelematic.director.daemon

import akka.Done
import com.advancedtelematic.director.db.DeviceRepositorySupport
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.DeleteDeviceRequest
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeleteDeviceRequestListener()(implicit val db: Database, val ec: ExecutionContext)
                                            extends MsgOperation[DeleteDeviceRequest]  with DeviceRepositorySupport {

  override def apply(message: DeleteDeviceRequest): Future[Unit] = {
    deviceRepository.markDeleted(message.namespace, message.uuid)
  }
}
