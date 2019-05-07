package com.advancedtelematic.director.daemon

import com.advancedtelematic.director.data.DataType.{MultiTargetUpdateRequest, TargetUpdate, TargetUpdateRequest}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.db.DeviceRepositorySupport
import com.advancedtelematic.director.http.DeviceRegistrationUtils
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.director.util.{DirectorSpec, DefaultPatience}
import com.advancedtelematic.libats.data.DataType.CampaignId
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType._
import com.advancedtelematic.libats.messaging_datatype.Messages._
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._
import com.advancedtelematic.libtuf.data.TufDataType.HardwareIdentifier
import java.time.Instant
import java.util.UUID

trait DeviceUpdateEventListenerSpec
    extends DirectorSpec
    with DefaultPatience
    with DeviceRegistrationUtils
    with DeviceRepositorySupport {

  implicit val messageBus = MessageBusPublisher.ignore
  val listener = new DeviceUpdateEventListener()

  val correlationId = CampaignId(UUID.randomUUID)

  testWithNamespace("can set multi-update targets") { implicit ns =>
    createRepoOk(testKeyType)
    val (device, primEcu, ecus) = registerDeviceOk(2)
    val primEcuHw = deviceRepository
      .findEcus(ns.get, device)
      .futureValue
      .find(_.ecuSerial == primEcu)
      .get
      .hardwareId
    val mtuId = createMtu(primEcuHw -> ((GenTargetUpdate.generate, false)))
    deviceQueueOk(device).length shouldBe 0

    val sourceUpdateId = SourceUpdateId(mtuId.uuid.toString)
    listener(DeviceUpdateAssignmentRequested(ns.get, Instant.now, correlationId, device, sourceUpdateId)).futureValue
    deviceQueueOk(device).length shouldBe 1
  }

  testWithNamespace("ignores unsupported SourceUpdateId") { implicit ns =>
    createRepoOk(testKeyType)
    val (device, primEcu, ecus) = registerDeviceOk(2)
    deviceQueueOk(device).length shouldBe 0

    val sourceUpdateId = SourceUpdateId("custom-unsupported-id")
    listener(DeviceUpdateAssignmentRequested(ns.get, Instant.now, correlationId, device, sourceUpdateId)).futureValue
    deviceQueueOk(device).length shouldBe 0
  }

  testWithNamespace("can cancel update") { implicit ns =>
    val (device, primEcu, ecus) = createDeviceWithImages(afn, bfn)
    setRandomTargetsWithCorrelationId(device, primEcu +: ecus, correlationId)
    deviceQueueOk(device).length shouldBe 1

    listener(DeviceUpdateCancelRequested(ns.get, Instant.now, correlationId, device)).futureValue
    deviceQueueOk(device).length shouldBe 0
  }

  testWithNamespace(s"can only cancel if update is not inflight") { implicit ns =>
    createRepoOk(testKeyType)
    val (device, primEcu, ecus) = createDeviceWithImages(afn, bfn)
    setRandomTargets(device, primEcu+:ecus, None)
    deviceQueueOk(device).length shouldBe 1

    makeUpdatesInflightFor(device)

    an[Exception] should be thrownBy(
      listener(DeviceUpdateCancelRequested(ns.get, Instant.now, correlationId, device)).futureValue)
    deviceQueueOk(device).length shouldBe 1
  }

  testWithNamespace("cancel several devices") { implicit ns =>
    createRepoOk(testKeyType)
    val (device1, primEcu1, ecus1) = createDeviceWithImages(afn, bfn)
    val (device2, primEcu2, ecus2) = createDeviceWithImages(afn, bfn)

    setRandomTargets(device1, primEcu1 +: ecus1, None)
    setRandomTargetsWithCorrelationId(device2, primEcu2 +: ecus2, correlationId)

    deviceQueueOk(device1).length shouldBe 1
    deviceQueueOk(device2).length shouldBe 1

    makeUpdatesInflightFor(device1)

    an[Exception] should be thrownBy(
      listener(DeviceUpdateCancelRequested(ns.get, Instant.now, correlationId, device1)).futureValue)
    deviceQueueOk(device1).length shouldBe 1

    listener(DeviceUpdateCancelRequested(ns.get, Instant.now, correlationId, device2)).futureValue
    deviceQueueOk(device2).length shouldBe 0
  }

  private def createMtu(hwimages: (HardwareIdentifier, (TargetUpdate, Boolean))*)(implicit ns: NamespaceTag): UpdateId = {
    val mtu = MultiTargetUpdateRequest(hwimages.toMap.mapValues{case (target, generateDiff) => TargetUpdateRequest(None, target, OSTREE, generateDiff)})
    createMultiTargetUpdateOK(mtu)
  }
}

class RsaDeviceUpdateEventListenerSpec extends DeviceUpdateEventListenerSpec with RsaGenerators

class EdDeviceUpdateEventListenerSpec extends DeviceUpdateEventListenerSpec with EdGenerators
