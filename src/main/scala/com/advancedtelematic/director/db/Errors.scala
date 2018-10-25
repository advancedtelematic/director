package com.advancedtelematic.director.db

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.ErrorCode
import com.advancedtelematic.libats.http.Errors.{EntityAlreadyExists, MissingEntity, RawError}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId

object ErrorCodes {
  val ConflictingSnapshot = ErrorCode("snapshot_already_exists")
  val ConflictingTarget = ErrorCode("target_already_exists")
  val ConflictingTimestamp = ErrorCode("timestamp_already_exists")
  val ConflictingRootFile = ErrorCode("root_already_exists")
  val MissingSnapshot = ErrorCode("snapshot_not_found")
  val MissingTarget = ErrorCode("target_not_found")
  val MissingTimestamp = ErrorCode("timestamp_not_found")
  val MissingRootFile = ErrorCode("root_not_found")
  val DeviceAlreadyRegistered = ErrorCode("device_already_registered")
  val DeviceMissingPrimaryEcu = ErrorCode("device_missing_primary_ecu")
  val EcuAlreadyRegistered = ErrorCode("ecu_already_registered")
  val MissingMultiTargetUpdate = ErrorCode("missing_multi_target_update")
  val MissingDevice = ErrorCode("missing_device")
  val CouldNotScheduleDevice = ErrorCode("could_not_schedule_device")
  val NoCacheEntry = ErrorCode("no_cache_entry")
  val CouldNotCancelUpdate = ErrorCode("could_not_cancel_update")
  val FetchingCancelledUpdate = ErrorCode("fetching_cancelled_update")
}

object Errors {
  val ConflictingFileCacheRequest = EntityAlreadyExists[FileCacheRequest]
  val MissingFileCacheRequest = MissingEntity[FileCacheRequest]

  val ConflictNamespaceRepo = EntityAlreadyExists[Namespace]
  val MissingNamespaceRepo = MissingEntity[Namespace]

  val ConflictingTarget = RawError(ErrorCodes.ConflictingTarget, StatusCodes.Conflict, "The target already exists")
  val MissingTarget = RawError(ErrorCodes.MissingTarget, StatusCodes.NotFound, "There is no target for device")

  val ConflictingSnapshot = RawError(ErrorCodes.ConflictingSnapshot, StatusCodes.Conflict, "The snapshot already exists")
  val MissingSnapshot = RawError(ErrorCodes.MissingSnapshot, StatusCodes.NotFound, "There is no snapshot for device")

  val ConflictingTimestamp = RawError(ErrorCodes.ConflictingTimestamp, StatusCodes.Conflict, "The timestamp already exists")
  val MissingTimestamp = RawError(ErrorCodes.MissingTimestamp, StatusCodes.NotFound, "There is no timestamp for device")

  val ConflictingRootFile = RawError(ErrorCodes.ConflictingRootFile, StatusCodes.Conflict, "The root already exists")
  val MissingRootFile = RawError(ErrorCodes.MissingRootFile, StatusCodes.NotFound, "There is no root")

  val NoTargetsScheduled = MissingEntity[DeviceUpdateTarget]
  val MissingCurrentTarget = MissingEntity[DeviceCurrentTarget]

  val DeviceAlreadyRegistered = RawError(ErrorCodes.DeviceAlreadyRegistered, StatusCodes.Conflict, "The device is already registered")
  val DeviceMissingPrimaryEcu = RawError(ErrorCodes.DeviceMissingPrimaryEcu, StatusCodes.NotFound, "The device don't have an ECU")
  val EcuAlreadyRegistered = RawError(ErrorCodes.EcuAlreadyRegistered, StatusCodes.Conflict, "The ecu is already registered")

  val ConflictingMultiTargetUpdate = EntityAlreadyExists[MultiTargetUpdateRow]
  val MissingMultiTargetUpdate = RawError(ErrorCodes.MissingMultiTargetUpdate, StatusCodes.NotFound, "multi-target update not found")

  val DeviceMissing = MissingEntity[DeviceId]

  val MissingDevice = RawError(ErrorCodes.MissingDevice, StatusCodes.NotFound, "The device is not found")
  val MissingEcu = MissingEntity[Ecu]

  val ConflictingAutoUpdate = EntityAlreadyExists[AutoUpdate]

  val CouldNotScheduleDevice = RawError(ErrorCodes.CouldNotScheduleDevice, StatusCodes.PreconditionFailed, "Could not schedule update on device")

  val NoCacheEntry = RawError(ErrorCodes.NoCacheEntry, StatusCodes.InternalServerError, "The requested version is not in the cache")

  val CouldNotCancelUpdate = RawError(ErrorCodes.CouldNotCancelUpdate, StatusCodes.PreconditionFailed, "Could not cancel update on device")

  val FetchingCancelledUpdate = RawError(ErrorCodes.FetchingCancelledUpdate, StatusCodes.InternalServerError, "Device tried to fetch cancelled update")
}
