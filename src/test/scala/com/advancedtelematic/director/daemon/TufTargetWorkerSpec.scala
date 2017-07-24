package com.advancedtelematic.director.daemon

import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.SetMultiTargets
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.HardwareIdentifier
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.OSTREE
import eu.timepit.refined.api.Refined

class TufTargetWorkerSpec extends DirectorSpec with ResourceSpec {
  val setMultiTargets = new SetMultiTargets
  val tufTargetWorker = new TufTargetWorker(setMultiTargets)

  val hw0: HardwareIdentifier = Refined.unsafeApply("hw0")
  val hw1: HardwareIdentifier = Refined.unsafeApply("hw1")
  val hw2: HardwareIdentifier = Refined.unsafeApply("hw2")

  val tu0 = GenTargetUpdate.generate
  val tu1 = GenTargetUpdate.generate

  test("createRequest hw shares") {
    val hwAndCurrent = Seq(hw0 -> tu0, hw1 -> tu1, hw0 -> tu0,
                           hw2 -> tu0, hw2 -> tu1)

    val mtu = tufTargetWorker.fromHwAndCurrentToRequest(hwAndCurrent, OSTREE, tu1)

    mtu.targets.foreach { case (hw, req) =>
      req.to shouldBe tu1
    }

    mtu.targets(hw0).from shouldBe Some(tu0)
    mtu.targets(hw1).from shouldBe Some(tu1)
    mtu.targets(hw2).from shouldBe None
  }

  test("createRequests will share mtu") {
    val hwAndCurrent = Seq(hw0 -> tu0)
    val device1 = DeviceId.generate
    val device2 = DeviceId.generate

    val mtus = tufTargetWorker.createRequests(Map(device1 -> hwAndCurrent,
                                                  device2 -> hwAndCurrent),
                                              OSTREE, tu1)
    mtus.size shouldBe 1

    mtus.foreach { case (mtu, devices) =>
      mtu.targets(hw0).from shouldBe Some(tu0)
      mtu.targets(hw0).to shouldBe tu1

      devices.toSet shouldBe Set(device1, device2)
    }

  }
}
