package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.codecs.CirceAnyVal._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.data.TufDataType.TargetName
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

class AutoUpdateSpec extends DirectorSpec with Requests with ResourceSpec {

  def findAutoUpdate(device: DeviceId, ecuSerial: EcuSerial)(implicit ns: NamespaceTag): Seq[TargetName] =
    Get(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[TargetName]]
    }

  def deleteAllAutoUpdate(device: DeviceId, ecuSerial: EcuSerial)(implicit ns: NamespaceTag): Unit =
    Delete(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }

  def setAutoUpdate(device: DeviceId, ecuSerial: EcuSerial, target: TargetName)(implicit ns: NamespaceTag): Unit = {
    Put(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update/${target.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  def deleteAutoUpdate(device: DeviceId, ecuSerial: EcuSerial, target: TargetName)(implicit ns: NamespaceTag): Unit = {
    Delete(apiUri(s"admin/devices/${device.show}/ecus/${ecuSerial.value}/auto_update/${target.value}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  def regDevice(howManyEcus: Int)(implicit ns: NamespaceTag): (DeviceId, EcuSerial, Seq[EcuSerial]) = {
    val device = DeviceId.generate
    val regEcus = (0 until (1 + howManyEcus)).map { _ =>
      GenRegisterEcu.generate
    }

    val primEcu = regEcus.head.ecu_serial

    registerDevice(RegisterDevice(device, primEcu, regEcus)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    (device, primEcu, regEcus.tail.map(_.ecu_serial))
  }

  test("fetching non-existent autoupdate yield empty") {
    withRandomNamespace { implicit ns =>
      val device = DeviceId.generate
      val ecuSerial= GenEcuSerial.generate

      findAutoUpdate(device, ecuSerial) shouldBe Seq()
    }
  }

  test("setting/unsetting targetName shows up") {
    withRandomNamespace { implicit ns =>
      val (device, primEcu, ecus) = regDevice(0)

      val targetName = GenTargetName.generate

      setAutoUpdate(device, primEcu, targetName)

      findAutoUpdate(device, primEcu) shouldBe Seq(targetName)

      deleteAutoUpdate(device, primEcu, targetName)

      findAutoUpdate(device, primEcu) shouldBe Seq()
    }
  }

  test("multiple ecus can have auto updates") {
    withRandomNamespace { implicit ns =>
      val (device, primEcu, Seq(ecu1, ecu2)) = regDevice(2)

      val targetName = GenTargetName.generate

      setAutoUpdate(device, ecu1, targetName)
      setAutoUpdate(device, ecu2, targetName)

      findAutoUpdate(device, ecu1) shouldBe Seq(targetName)
      findAutoUpdate(device, ecu2) shouldBe Seq(targetName)

      deleteAutoUpdate(device, ecu1, targetName)
      deleteAutoUpdate(device, ecu2, targetName)

      findAutoUpdate(device, ecu1) shouldBe Seq()
      findAutoUpdate(device, ecu2) shouldBe Seq()
    }
  }

  test("ecu can have multiple auto updates") {
    withRandomNamespace { implicit ns =>
      val (device, primEcu, ecus) = regDevice(0)

      val targetName1 = GenTargetName.generate
      val targetName2 = GenTargetName.generate

      setAutoUpdate(device, primEcu, targetName1)
      setAutoUpdate(device, primEcu, targetName2)

      findAutoUpdate(device, primEcu).toSet shouldBe Set(targetName1, targetName2)

      deleteAutoUpdate(device, primEcu, targetName1)
      findAutoUpdate(device, primEcu) shouldBe Seq(targetName2)

      deleteAutoUpdate(device, primEcu, targetName2)
      findAutoUpdate(device, primEcu) shouldBe Seq()

      setAutoUpdate(device, primEcu, targetName1)
      setAutoUpdate(device, primEcu, targetName2)
      findAutoUpdate(device, primEcu).toSet shouldBe Set(targetName1, targetName2)

      deleteAllAutoUpdate(device, primEcu)
      findAutoUpdate(device, primEcu) shouldBe Seq()
    }

  }
}
