package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.DeviceRequest.EcuManifest
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libtuf.data.TufDataType.SignedPayload

object Legacy {
  final case class LegacyDeviceManifest(primary_ecu_serial: EcuIdentifier,
                                        ecu_version_manifest: Seq[SignedPayload[EcuManifest]])

}
