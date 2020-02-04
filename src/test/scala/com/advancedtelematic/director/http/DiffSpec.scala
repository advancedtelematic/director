package com.advancedtelematic.director.http

import com.advancedtelematic.diff_service.data.DataType.CreateDiffInfoRequest
import com.advancedtelematic.director.data.Codecs.decoderTargetCustom
import com.advancedtelematic.director.data.DataType.TargetCustom
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.db.FileCacheDB
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.data.TufDataType.ValidTargetFilename
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.OSTREE

trait DiffSpec extends DeviceUpdateSpec with FileCacheDB {

  testWithNamespace("device waits for diff") { implicit ns =>
    createRepo().futureValue
    val device = registerNSDeviceOk(ahw -> ato)
    generateAllPendingFiles().futureValue

    val update = createMtu(ahw -> ((bto, true)))
    val diffRequests = Seq(CreateDiffInfoRequest(OSTREE, ato, bto))
    diffServiceClient.createDiffInfo(ns.get, diffRequests).futureValue

    var timestamp = fetchTimestampFor(device)
    timestamp.signed.version shouldBe 0

    scheduleOne(device, update)

    timestamp = fetchTimestampFor(device)
    timestamp.signed.version shouldBe 0

    val diffInfo = GenDiffInfo.generate
    diffServiceClient.generate(OSTREE, ato, bto, diffInfo)

    timestamp = fetchTimestampFor(device)
    timestamp.signed.version shouldBe 1

    val targets = fetchTargetsFor(device)
    targets.signed.targets(bto.target.value.refineTry[ValidTargetFilename].get).custom.get.as[TargetCustom].right.get.diff.get shouldBe diffInfo
  }
}

class RsaDiffSpec extends DiffSpec with RsaGenerators

class EdDiffSpec extends DiffSpec with EdGenerators
