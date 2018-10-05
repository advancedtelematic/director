package com.advancedtelematic.director.http

import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.data.DataType.{CorrelationId, TargetsCustom}
import com.advancedtelematic.director.data.Codecs.targetsCustomDecoder

trait DeviceMtuSpec extends DeviceUpdateSpec {

  testWithNamespace("PUT device/multi_target_update sets correlationId") { implicit ns =>
    createRepo().futureValue
    val device = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val update = createMtu(ahw -> ((bto, false)))
    scheduleOne(device, update)

    val targets = fetchTargetsFor(device)
    targets.signed.custom.get.as[TargetsCustom].right.get shouldBe TargetsCustom(Some(CorrelationId.from(update)))
  }
}

class RsaDeviceMtuSpec extends DeviceMtuSpec with RsaGenerators

class EdDeviceMtuSpec extends DeviceMtuSpec with EdGenerators
