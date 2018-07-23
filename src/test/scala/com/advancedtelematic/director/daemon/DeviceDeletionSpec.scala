package com.advancedtelematic.director.daemon

import com.advancedtelematic.director.data.{EdGenerators, KeyGenerators}
import com.advancedtelematic.director.data.AdminRequest.RegisterDevice
import com.advancedtelematic.director.db.{AdminRepository, Errors}
import com.advancedtelematic.director.http.NamespacedRequests
import com.advancedtelematic.director.util.{DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import org.scalatest.concurrent.ScalaFutures

class DeviceDeletionSpec
    extends DirectorSpec
    with RouteResourceSpec
    with KeyGenerators
    with NamespacedRequests
    with EdGenerators
    with ScalaFutures {
  import com.advancedtelematic.director.data.GeneratorOps._

  testWithNamespace("Device will be deleted on DeleteDeviceRequest event") { implicit ns =>
    val device     = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu    = primEcuReg.ecu_serial
    val ecus = GenRegisterEcu
      .atMost(5)
      .generate ++ (primEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    import org.scalatest.time.SpanSugar._
    val repo = new AdminRepository()
    new DeleteDeviceHandler(repo)
      .deleteDevice(DeleteDeviceHandler.DeleteDeviceRequest(ns.get, device))
      .isReadyWithin(500.millis)

    repo.findDevice(ns.get, device).failed.futureValue shouldBe Errors.MissingDevice
  }
}
