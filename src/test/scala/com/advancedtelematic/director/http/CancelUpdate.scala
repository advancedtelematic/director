package com.advancedtelematic.director.http

import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}

trait CancelUpdateSpec extends DirectorSpec
    with DefaultPatience
    with DeviceRegistrationUtils
    with RouteResourceSpec
    with NamespacedRequests {

  testWithNamespace("can cancel update") { implicit ns =>
    val (device, primEcu, ecus) = createDeviceWithImages(afn, bfn)

    setRandomTargets(device, primEcu+:ecus)

    getAssignmentsOk(device).length shouldBe 1

    cancelAssignmentOk(device)

    getAssignmentsOk(device).length shouldBe 0
  }

  testWithNamespace(s"can only cancel if update is not inflight") { implicit ns =>
    createRepoOk(testKeyType)
    val (device, primEcu, ecus) = createDeviceWithImages(afn, bfn)

    setRandomTargets(device, primEcu+:ecus, None)

    getAssignmentsOk(device).length shouldBe 1

    //make it inflight
    val t = fetchTargetsFor(device)
    t.signed.version shouldBe 1

    cancelAssignmentFail(device)

    getAssignmentsOk(device).length shouldBe 1
  }

  testWithNamespace("cancel several devices") { implicit ns =>
    createRepoOk(testKeyType)
    val (device1, primEcu1, ecus1) = createDeviceWithImages(afn, bfn)
    val (device2, primEcu2, ecus2) = createDeviceWithImages(afn, bfn)

    setRandomTargets(device1, primEcu1+:ecus1, None)
    setRandomTargets(device2, primEcu2+:ecus2)

    getAssignmentsOk(device1).length shouldBe 1
    getAssignmentsOk(device2).length shouldBe 1

    //make device1 inflight
    val t = fetchTargetsFor(device1)
    t.signed.version shouldBe 1

    cancelAssignments(device1, device2) shouldBe Seq(device2)

    getAssignmentsOk(device1).length shouldBe 1
    getAssignmentsOk(device2).length shouldBe 0
  }
}

class RsaCancelUpdateSpec extends CancelUpdateSpec with RsaGenerators

class EdCancelUpdateSpec extends CancelUpdateSpec with EdGenerators
