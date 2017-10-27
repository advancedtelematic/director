package com.advancedtelematic.diff_service.data

import com.advancedtelematic.diff_service.data.DataType._
import com.advancedtelematic.diff_service.data.DataType.DiffStatus.DiffStatus
import com.advancedtelematic.director.data.Codecs.{decoderTargetUpdate, encoderTargetUpdate}
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.http.HttpCodecs._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

object Codecs {
  implicit val decoderDiffStatus: Decoder[DiffStatus] = Decoder.enumDecoder(DiffStatus)
  implicit val encoderDiffStatus: Encoder[DiffStatus] = Encoder.enumEncoder(DiffStatus)

  implicit val decoderCreatDiffInfoRequest: Decoder[CreateDiffInfoRequest] = deriveDecoder
  implicit val encoderCreatDiffInfoRequest: Encoder[CreateDiffInfoRequest] = deriveEncoder

  implicit val decoderBsDiffInfo: Decoder[BsDiffInfo] = deriveDecoder
  implicit val encoderBsDiffInfo: Encoder[BsDiffInfo] = deriveEncoder

  implicit val decoderStaticDeltaInfo: Decoder[StaticDeltaInfo] = deriveDecoder
  implicit val encoderStaticDeltaInfo: Encoder[StaticDeltaInfo] = deriveEncoder

  implicit val decoderBsDiffQuery: Decoder[BsDiffQuery] = deriveDecoder
  implicit val encoderBsDiffQuery: Encoder[BsDiffQuery] = deriveEncoder

  implicit val decoderStaticDeltaQuery: Decoder[StaticDeltaQuery] = deriveDecoder
  implicit val encoderStaticDeltaQuery: Encoder[StaticDeltaQuery] = deriveEncoder

  implicit val decoderBsDiffQueryResponse: Decoder[BsDiffQueryResponse] = deriveDecoder
  implicit val encoderBsDiffQueryResponse: Encoder[BsDiffQueryResponse] = deriveEncoder

  implicit val decoderStaticDeltaQueryResponse: Decoder[StaticDeltaQueryResponse] = deriveDecoder
  implicit val encoderStaticDeltaQueryResponse: Encoder[StaticDeltaQueryResponse] = deriveEncoder
}
