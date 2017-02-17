package com.advancedtelematic.director.db

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType._
import org.genivi.sota.data.Namespace

import org.genivi.sota.http.Errors.{EntityAlreadyExists, MissingEntity, RawError}
import org.genivi.sota.rest.ErrorCode

object ErrorCodes {
  val ConflictingSnapshot = ErrorCode("snapshot_already_exists")
  val ConflictingTarget = ErrorCode("target_already_exists")
  val MissingSnapshot = ErrorCode("snapshot_not_found")
  val MissingTarget = ErrorCode("target_not_found")
}

object Errors {
  val ConflictingFileCacheRequest = EntityAlreadyExists(classOf[FileCacheRequest])
  val MissingFileCacheRequest = MissingEntity(classOf[FileCacheRequest])

  val MissingNamespaceRepo = MissingEntity(classOf[Namespace])

  val ConflictingTarget = RawError(ErrorCodes.ConflictingTarget, StatusCodes.Conflict, "The target already exists")
  val MissingTarget = RawError(ErrorCodes.MissingTarget, StatusCodes.NotFound, "There is no target for device")

  val ConflictingSnapshot = RawError(ErrorCodes.ConflictingSnapshot, StatusCodes.Conflict, "The snapshot already exists")
  val MissingSnapshot = RawError(ErrorCodes.MissingTarget, StatusCodes.NotFound, "There is no snapshot for device")

  val NoTargetsScheduled = MissingEntity(classOf[DeviceTargets])
  val MissingCurrentTarget = MissingEntity(classOf[DeviceCurrentTarget])
}
