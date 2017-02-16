package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import org.genivi.sota.http.Errors.RawError
import org.genivi.sota.rest.ErrorCode

object ErrorCodes {
  val TargetsNotSubSetOfDevice = ErrorCode("targets-not-subset-of-device")
  val DeviceUpdatedToWrongTarget = ErrorCode("device-updated-to-wrong-target")
}

object Errors {
  val TargetsNotSubSetOfDevice = RawError(ErrorCodes.TargetsNotSubSetOfDevice, StatusCodes.BadRequest, "The given targets include ecus that don't belong to the device")
  val DeviceUpdatedToWrongTarget = RawError(ErrorCodes.DeviceUpdatedToWrongTarget, StatusCodes.BadRequest, "The device did not update to the correct target")
}
