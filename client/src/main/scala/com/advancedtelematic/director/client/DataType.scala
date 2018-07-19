package com.advancedtelematic.director.client

import com.advancedtelematic.director.client.DataType.SetMultiTargetUpdate
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import io.circe.Json

object Codecs {
  import io.circe.Encoder
  import io.circe.Decoder
  import io.circe.generic.semiauto._

  implicit val setMultiUpdateTargetEncoder: Encoder[SetMultiTargetUpdate] = deriveEncoder
  implicit val setMultiUpdateTargetDecoder: Decoder[SetMultiTargetUpdate] = deriveDecoder
}


object DataType {
  case class SetMultiTargetUpdate(devices: Seq[DeviceId], campaignMetadata: Json = Json.arr())
}
