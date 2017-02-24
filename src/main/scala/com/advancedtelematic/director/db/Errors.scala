package com.advancedtelematic.director.db

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType._

import com.advancedtelematic.libats.http.Errors.{EntityAlreadyExists, MissingEntity, RawError}
import com.advancedtelematic.libats.http.ErrorCode

object ErrorCodes {
  val ConflictingSnapshot = ErrorCode("snapshot_already_exists")
  val ConflictingTarget = ErrorCode("target_already_exists")
  val ConflictingTimestamp = ErrorCode("timestamp_already_exists")
  val ConflictingRootFile = ErrorCode("root_already_exists")
  val MissingSnapshot = ErrorCode("snapshot_not_found")
  val MissingTarget = ErrorCode("target_not_found")
  val MissingTimestamp = ErrorCode("timestamp_not_found")
  val MissingRootFile = ErrorCode("root_not_found")
}

object Errors {
  val ConflictingFileCacheRequest = EntityAlreadyExists(classOf[FileCacheRequest])
  val MissingFileCacheRequest = MissingEntity(classOf[FileCacheRequest])

  val ConflictNamespaceRepo = EntityAlreadyExists(classOf[Namespace])
  val MissingNamespaceRepo = MissingEntity(classOf[Namespace])

  val ConflictingTarget = RawError(ErrorCodes.ConflictingTarget, StatusCodes.Conflict, "The target already exists")
  val MissingTarget = RawError(ErrorCodes.MissingTarget, StatusCodes.NotFound, "There is no target for device")

  val ConflictingSnapshot = RawError(ErrorCodes.ConflictingSnapshot, StatusCodes.Conflict, "The snapshot already exists")
  val MissingSnapshot = RawError(ErrorCodes.MissingSnapshot, StatusCodes.NotFound, "There is no snapshot for device")

  val ConflictingTimestamp = RawError(ErrorCodes.ConflictingTimestamp, StatusCodes.Conflict, "The timestamp already exists")
  val MissingTimestamp = RawError(ErrorCodes.MissingTimestamp, StatusCodes.NotFound, "There is no timestamp for device")

  val ConflictingRootFile = RawError(ErrorCodes.ConflictingRootFile, StatusCodes.Conflict, "The root already exists")
  val MissingRootFile = RawError(ErrorCodes.MissingRootFile, StatusCodes.NotFound, "There is no root")

  val NoTargetsScheduled = MissingEntity(classOf[DeviceTargets])
  val MissingCurrentTarget = MissingEntity(classOf[DeviceCurrentTarget])
}
