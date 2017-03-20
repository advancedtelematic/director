package com.advancedtelematic.director.daemon

import com.advancedtelematic.director.data.DataType.{FileInfo, Image}
import com.advancedtelematic.director.http.Requests
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import org.scalatest.concurrent.Eventually._

class MultiTargetUpdateCreatedWorkerSpec extends DirectorSpec with DefaultPatience with Requests with ResourceSpec {

  import com.advancedtelematic.director.data.GeneratorOps._

  test("Multi-target update action should save messages in database") {
    val mtu = GenMultiTargetUpdateCreated.generate
    val clientHash1 = Map(mtu.checksum.method -> mtu.checksum.hash)
    val expectedResult = Map(mtu.hardwareId -> Image(mtu.target, FileInfo(clientHash1, mtu.targetLength)))
    MultiTargetUpdateWorker.action(mtu)
    eventually {
      fetchMultiTargetUpdate(mtu.id) shouldBe expectedResult
    }
  }
}
