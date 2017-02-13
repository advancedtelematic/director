package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import org.genivi.sota.http.Errors.RawError
import org.genivi.sota.rest.ErrorCode

object ErrorCodes {
  val TargetsNotSubSetOfDevice = ErrorCode("targets-not-subset-of-device")
}

object Errors {
  val TargetsNotSubSetOfDevice = RawError(ErrorCodes.TargetsNotSubSetOfDevice, StatusCodes.BadRequest, "The given targets include ecus that don't belong to the device")
}
