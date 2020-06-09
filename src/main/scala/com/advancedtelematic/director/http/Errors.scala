package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.Show
import cats.syntax.show
import com.advancedtelematic.director.data.AdminDataType.MultiTargetUpdate
import com.advancedtelematic.director.data.DataType.DeviceUpdateTarget
import com.advancedtelematic.director.data.DbDataType.Ecu
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{EcuIdentifier, ErrorCode}
import com.advancedtelematic.libats.http.Errors.{EntityAlreadyExists, MissingEntity, MissingEntityId, RawError}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.ClientDataType.TufRole
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType

object ErrorCodes {
  val PrimaryIsNotListedForDevice = ErrorCode("primary_is_not_listed_for_device")

  val DeviceMissingPrimaryEcu = ErrorCode("device_missing_primary_ecu")
  val NoRepoForNamespace = ErrorCode("no_repo_for_namespace")

  object Manifest {
    val EcuNotPrimary = ErrorCode("ecu_not_primary")
    val WrongEcuSerialInEcuManifest = ErrorCode("wrong_ecu_serial_not_in_ecu_manifest")
    val SignatureNotValid = ErrorCode("signature_not_valid")
  }

  val ReplaceEcuAssignmentExists = ErrorCode("replace_ecu_assignment_exists")
  val EcuReuseError = ErrorCode("ecu_reuse_not_allowed")
}

object Errors {
  val PrimaryIsNotListedForDevice = RawError(ErrorCodes.PrimaryIsNotListedForDevice, StatusCodes.BadRequest, "The given primary ecu isn't part of ecus for the device")

  val DeviceMissingPrimaryEcu = RawError(ErrorCodes.DeviceMissingPrimaryEcu, StatusCodes.NotFound, "The device don't have an ECU")

  def AssignmentExistsError(deviceId: DeviceId) = RawError(ErrorCodes.ReplaceEcuAssignmentExists, StatusCodes.PreconditionFailed, s"Cannot replace ecus for $deviceId, the device has running assignments")

  def EcusReuseError(deviceId: DeviceId, ecuIds: Seq[EcuIdentifier]) =
    RawError(ErrorCodes.EcuReuseError, StatusCodes.Conflict, s"At least one ecu in ${ecuIds.mkString(",")} was already used and removed for $deviceId and cannot be reused")

  def InvalidVersionBumpError(oldVersion: Int, newVersion: Int, roleType: RoleType) =
    RawError(ErrorCode("invalid_version_bump"), StatusCodes.Conflict, s"Cannot bump version from $oldVersion to $newVersion for $roleType")

  private val showDeviceIdRoleTypeTuple: Show[(DeviceId, TufRole[_])] = Show.show { case (did, tufRole) => s"No tuf role ${tufRole.metaPath} found for $did"}

  def SignedRoleNotFound[T](deviceId: DeviceId)(implicit ev: TufRole[T]) =
    MissingEntityId[(DeviceId, TufRole[_])](deviceId -> ev)(ct = implicitly, show = showDeviceIdRoleTypeTuple)

  case class NoRepoForNamespace(ns: Namespace)
    extends com.advancedtelematic.libats.http.Errors.Error(ErrorCodes.NoRepoForNamespace, StatusCodes.NotFound, s"No repository exists for namespace ${ns.get}")

  object Manifest {
    val EcuNotPrimary = RawError(ErrorCodes.Manifest.EcuNotPrimary, StatusCodes.BadRequest, "The claimed primary ECU is not the primary ECU for the device")

    def SignatureNotValid(err: String) = RawError(ErrorCodes.Manifest.SignatureNotValid, StatusCodes.BadRequest, s"The given signature is not valid: $err")
  }
}
