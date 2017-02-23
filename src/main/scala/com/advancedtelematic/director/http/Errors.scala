package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.libats.http.Errors.RawError
import com.advancedtelematic.libats.http.ErrorCode

object ErrorCodes {
  val TargetsNotSubSetOfDevice = ErrorCode("targets_not_subset_of_device")
  val DeviceUpdatedToWrongTarget = ErrorCode("device_updated_to_wrong_target")
  val PrimaryIsNotListedForDevice = ErrorCode("primary_is_not_listed_for_device")
}

object Errors {
  val TargetsNotSubSetOfDevice = RawError(ErrorCodes.TargetsNotSubSetOfDevice, StatusCodes.BadRequest, "The given targets include ecus that don't belong to the device")
  val DeviceUpdatedToWrongTarget = RawError(ErrorCodes.DeviceUpdatedToWrongTarget, StatusCodes.BadRequest, "The device did not update to the correct target")
  val PrimaryIsNotListedForDevice = RawError(ErrorCodes.PrimaryIsNotListedForDevice, StatusCodes.BadRequest, "The given primary ecu isn't part of ecus for the device")
}
