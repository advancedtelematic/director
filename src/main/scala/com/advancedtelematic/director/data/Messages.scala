package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus.UpdateStatus
import com.advancedtelematic.director.data.MessageDataType.SOTA_Instant
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import java.time.Instant
import java.util.UUID

object MessageDataType {

  object UpdateStatus extends Enumeration {
    type UpdateStatus = Value

    val Pending, InFlight, Canceled, Failed, Finished = Value
  }

  final case class SOTA_Instant(inner: Instant) extends AnyVal

  object SOTA_Instant {
    def now(): SOTA_Instant = SOTA_Instant(Instant.now())
  }
}

object MessageCodecs {
  import io.circe.{Decoder, DecodingFailure, Encoder, Json}
  import io.circe.generic.semiauto._
  import java.time.format.{DateTimeFormatter, DateTimeParseException}

  implicit val updateStatusEncoder: Encoder[UpdateStatus] = Encoder.enumEncoder(UpdateStatus)
  implicit val updateStatusDecoder: Decoder[UpdateStatus] = Decoder.enumDecoder(UpdateStatus)

  implicit val dateTimeEncoder : Encoder[SOTA_Instant] =
    Encoder.instance[SOTA_Instant]( x =>  Json.fromString( x.inner.toString) )

  implicit val dateTimeDecoder : Decoder[SOTA_Instant] = Decoder.instance { c =>
    c.focus.flatMap(_.asString) match {
      case None       => Left(DecodingFailure("DataTime", c.history))
      case Some(date) =>
        try {
          val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
          val nst = SOTA_Instant(Instant.from(fmt.parse(date)))
          Right(nst)
        }
        catch {
          case t: DateTimeParseException =>
            Left(DecodingFailure("DateTime", c.history))
        }
    }
  }

  implicit val updateSpecEncoder: Encoder[UpdateSpec] = deriveEncoder
  implicit val updateSpecDecoder: Decoder[UpdateSpec] = deriveDecoder
}

object Messages {
  import MessageCodecs._

  final case class UpdateSpec(namespace: Namespace, device: DeviceId, packageUuid: UpdateId, status: UpdateStatus, timestamp: SOTA_Instant = SOTA_Instant.now())

  object UpdateSpec {
    def apply(namespace: Namespace, device: DeviceId, status: UpdateStatus): UpdateSpec = {
      val pkgUuid = UpdateId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
      UpdateSpec(namespace, device, pkgUuid, status)
    }
  }

  implicit val updateSpecLike = MessageLike[UpdateSpec](_.device.toString)
}
