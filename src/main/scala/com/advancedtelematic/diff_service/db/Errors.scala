package com.advancedtelematic.diff_service.db

import com.advancedtelematic.diff_service.data.DataType.{BsDiff, BsDiffInfo, StaticDelta, StaticDeltaInfo}
import com.advancedtelematic.libats.http.Errors.{EntityAlreadyExists, MissingEntity}

object Errors {
  val ConflictingBsDiff = EntityAlreadyExists[BsDiff]
  val MissingBsDiff = MissingEntity[BsDiff]

  val ConflictingBsDiffInfo = EntityAlreadyExists[BsDiffInfo]
  val MissingBsDiffInfo = MissingEntity[BsDiffInfo]

  val ConflictingStaticDelta = EntityAlreadyExists[StaticDelta]
  val MissingStaticDelta = MissingEntity[StaticDelta]

  val ConflictingStaticDeltaInfo = EntityAlreadyExists[StaticDeltaInfo]
  val MissingStaticDeltaInfo = MissingEntity[StaticDeltaInfo]
}
