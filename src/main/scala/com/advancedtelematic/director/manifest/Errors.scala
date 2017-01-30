package com.advancedtelematic.director.manifest

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType.Ecu
import org.genivi.sota.http.Errors.{MissingEntity, RawError}
import org.genivi.sota.rest.ErrorCode

object ErrorCodes {
  val EcuNotPrimary = ErrorCode("ecu_not_primary")
  val EmptySignatureList = ErrorCode("empty_signature_list")
  val SignatureMethodMismatch = ErrorCode("signature_method_mismatch")
  val SignatureNotValid = ErrorCode("signature_not_valid")
}

object Errors {
  val EcuNotFound = MissingEntity(classOf[Ecu])
  val EcuNotPrimary = RawError(ErrorCodes.EcuNotPrimary, StatusCodes.BadRequest, "The claimed primary ECU is not the primary ECU for the device")
  val EmptySignatureList = RawError(ErrorCodes.EmptySignatureList, StatusCodes.BadRequest, "The given signature list is empty")
  val SignatureMethodMismatch = RawError(ErrorCodes.SignatureMethodMismatch, StatusCodes.BadRequest, "The given signature method and the stored signature method are different")
  val SignatureNotValid = RawError(ErrorCodes.SignatureNotValid, StatusCodes.BadRequest, "The given signature is not valid")
}
