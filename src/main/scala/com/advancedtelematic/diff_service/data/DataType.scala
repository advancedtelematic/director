package com.advancedtelematic.diff_service.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.diff_service.data.DataType.DiffStatus.DiffStatus
import com.advancedtelematic.director.data.DataType.TargetUpdate
import com.advancedtelematic.libats.codecs.CirceEnum
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.slick.codecs.SlickEnum
import com.advancedtelematic.libats.messaging_datatype.DataType.{BsDiffRequestId, Checksum, Commit, DeltaRequestId}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat

object DataType {
  object DiffStatus extends CirceEnum with SlickEnum {
    type DiffStatus = Value

    val REQUESTED, GENERATED, FAILED = Value
  }

  final case class BsDiff(namespace: Namespace, id: BsDiffRequestId, from: Uri, to: Uri, status: DiffStatus)
  final case class BsDiffInfo(id: BsDiffRequestId, checksum: Checksum, size: Long, resultUri: Uri)

  final case class StaticDelta(namespace: Namespace, id: DeltaRequestId, from : Commit, to: Commit, status: DiffStatus)
  final case class StaticDeltaInfo(id: DeltaRequestId, checksum: Checksum, size: Long, resultUri: Uri)

  final case class CreateDiffInfoRequest(format: TargetFormat, from: TargetUpdate, to: TargetUpdate)

  final case class BsDiffQuery(from: Uri, to: Uri)
  final case class BsDiffQueryResponse(id: BsDiffRequestId, status: DiffStatus, diff: Option[BsDiffInfo])

  final case class StaticDeltaQuery(from: Commit, to: Commit)
  final case class StaticDeltaQueryResponse(id: DeltaRequestId, status: DiffStatus, diff: Option[StaticDeltaInfo])
}
