package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.{EdGenerators, RsaGenerators}
import com.advancedtelematic.director.http.DeviceRegistrationUtils
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec}
import com.advancedtelematic.libats.data.DataType.CampaignId
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCanceled
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

final class TestMessageBus extends MessageBusPublisher {
  var messages = Seq.empty[Any]

  override def publish[T](msg: T)(implicit ex: ExecutionContext, messageLike: MessageLike[T]): Future[Unit] = {
    messages = messages :+ msg
    Future.successful(())
  }
}

trait CancelUpdateSpec extends DirectorSpec
    with DefaultPatience
    with DeviceRegistrationUtils {

  testWithNamespace("can cancel update") { implicit ns =>
    val correlationId = CampaignId(UUID.randomUUID)
    val (device, primEcu, ecus) = createDeviceWithImages(afn, bfn)
    setRandomTargetsWithCorrelationId(device, primEcu +: ecus, correlationId)

    deviceQueueOk(device).length shouldBe 1

    val testMsgBus = new TestMessageBus()
    val cancelUpdate = new CancelUpdate()(implicitly[Database], implicitly[ExecutionContext], testMsgBus)
    cancelUpdate.one(ns.get, device).futureValue

    deviceQueueOk(device).length shouldBe 0

    testMsgBus.messages.length shouldBe 2

    val updateSpecMsg = testMsgBus.messages(0).asInstanceOf[UpdateSpec]
    updateSpecMsg.namespace shouldBe ns.get
    updateSpecMsg.device shouldBe device
    updateSpecMsg.status shouldBe UpdateStatus.Canceled

    val deviceUpdateCanceledMsg = testMsgBus.messages(1).asInstanceOf[DeviceUpdateCanceled]
    deviceUpdateCanceledMsg.namespace shouldBe ns.get
    deviceUpdateCanceledMsg.correlationId shouldBe correlationId
    deviceUpdateCanceledMsg.deviceUuid shouldBe device
  }

  testWithNamespace(s"can only cancel if update is not inflight") { implicit ns =>
    createRepoOk(testKeyType)
    val (device, primEcu, ecus) = createDeviceWithImages(afn, bfn)

    setRandomTargets(device, primEcu+:ecus, None)

    deviceQueueOk(device).length shouldBe 1

    makeUpdatesInflightFor(device)

    val cancelUpdate = new CancelUpdate()
    an[Exception] should be thrownBy cancelUpdate.one(ns.get, device).futureValue

    deviceQueueOk(device).length shouldBe 1
  }

  testWithNamespace("cancel several devices") { implicit ns =>
    val correlationId = CampaignId(UUID.randomUUID)
    createRepoOk(testKeyType)
    val (device1, primEcu1, ecus1) = createDeviceWithImages(afn, bfn)
    val (device2, primEcu2, ecus2) = createDeviceWithImages(afn, bfn)

    setRandomTargets(device1, primEcu1 +: ecus1, None)
    setRandomTargetsWithCorrelationId(device2, primEcu2 +: ecus2, correlationId)

    deviceQueueOk(device1).length shouldBe 1
    deviceQueueOk(device2).length shouldBe 1

    makeUpdatesInflightFor(device1)

    val testMsgBus = new TestMessageBus()
    val cancelUpdate = new CancelUpdate()(implicitly[Database], implicitly[ExecutionContext], testMsgBus)
    val cancelledTargets = cancelUpdate.several(ns.get, Seq(device1, device2)).futureValue
    cancelledTargets.map(_.deviceId) shouldBe Seq(device2)

    deviceQueueOk(device1).length shouldBe 1
    deviceQueueOk(device2).length shouldBe 0

    testMsgBus.messages.length shouldBe 2

    val updateSpecMsg = testMsgBus.messages(0).asInstanceOf[UpdateSpec]
    updateSpecMsg.namespace shouldBe ns.get
    updateSpecMsg.device shouldBe device2
    updateSpecMsg.status shouldBe UpdateStatus.Canceled

    val deviceUpdateCanceledMsg = testMsgBus.messages(1).asInstanceOf[DeviceUpdateCanceled]
    deviceUpdateCanceledMsg.namespace shouldBe ns.get
    deviceUpdateCanceledMsg.correlationId shouldBe correlationId
    deviceUpdateCanceledMsg.deviceUuid shouldBe device2
  }
}

class RsaCancelUpdateSpec extends CancelUpdateSpec with RsaGenerators

class EdCancelUpdateSpec extends CancelUpdateSpec with EdGenerators
