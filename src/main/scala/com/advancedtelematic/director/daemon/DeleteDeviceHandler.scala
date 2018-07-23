package com.advancedtelematic.director.daemon

import com.advancedtelematic.director.db.AdminRepository
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import io.circe.{Decoder, Encoder}

import scala.concurrent.Future

object DeleteDeviceHandler {
  final case class DeleteDeviceRequest(ns: Namespace, id: DeviceId)

  object DeleteDeviceRequest {
    import com.advancedtelematic.libats.codecs.CirceCodecs.{namespaceDecoder, namespaceEncoder}
    private[this] implicit val DecoderInstance: Decoder[DeleteDeviceRequest] =
      Decoder.forProduct2("ns", "uuid")(DeleteDeviceRequest.apply)

    implicitly[Encoder[Namespace]]
    implicitly[Encoder[DeviceId]]
    private[this] implicit val EncoderInstance = io.circe.generic.semiauto.deriveEncoder[DeleteDeviceRequest]

    implicit val MessageLikeInstance = MessageLike[DeleteDeviceRequest](_.id.uuid.toString)
  }
}

class DeleteDeviceHandler(repo: AdminRepository) {
  import DeleteDeviceHandler.DeleteDeviceRequest

  def deleteDevice(command: DeleteDeviceRequest): Future[Int] = {
    repo.deleteDevice(command.ns, command.id)
  }

}
