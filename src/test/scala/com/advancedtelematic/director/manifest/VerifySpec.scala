package com.advancedtelematic.director.manifest

import java.security.KeyPair
import java.time.Instant
import java.time.temporal.ChronoUnit

import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.Ecu
import com.advancedtelematic.director.data.DeviceRequest.{EcuManifest, DeviceManifest, LegacyDeviceManifest}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.crypt.TufCrypto.PublicKeyOps
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{EdKeyType, KeyType, RsaKeyType, SignedPayload}
import io.circe.{Decoder, Encoder}
import io.circe.syntax._

abstract class VerifySpec
    extends DirectorSpec
    with DefaultPatience
{
  val keytype: KeyType
  val keySize: Int


  def generateKey: KeyPair = {
    val (pub, sec) = TufCrypto.generateKeyPair(keytype, keySize)
    new KeyPair(pub.keyval, sec.keyval)
  }

  def sign[T : Encoder : Decoder](key: KeyPair, payload: T): SignedPayload[T] = {
    val signature = TufCrypto
      .sign(keytype, key.getPrivate, payload.asJson.canonical.getBytes)
      .toClient(key.getPublic.id)

    SignedPayload(List(signature), payload)
  }

  val namespace = Namespace("verify-spec")

  def generateKeyAndEcuManifest: (KeyPair, Ecu, EcuSerial, EcuManifest) = {
    val deviceId = DeviceId.generate
    val keys = generateKey

    val primEcu = GenEcuSerial.generate

    val ecu = Ecu(primEcu, deviceId, namespace, true, GenHardwareIdentifier.generate, TufCrypto.convert(keytype, keys.getPublic))
    val ecus = Seq(ecu)

    val time = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val pretime = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val ecuMan = GenEcuManifest(primEcu).generate.copy(timeserver_time = time,
                                                       previous_timeserver_time = pretime)

    (keys, ecu, primEcu, ecuMan)
  }

  test("correctly verifies correct signature for device manifest") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val sEcu = sign(keys, ecuMan)

    val devMan = DeviceManifest(primEcu, Map( primEcu -> sEcu.asJson))
    val sdevMan = sign(keys, devMan)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus shouldBe Seq(ecuMan)
  }

  test("correctly verifies correct signature for legacy device manifest") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val sEcu = sign(keys, ecuMan)

    val devMan = LegacyDeviceManifest(primEcu, Seq(sEcu))
    val sdevMan = sign(keys, devMan)

    val vEcus = Verify.legacyDeviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus shouldBe Seq(ecuMan)
  }

  test("ecu manifest that doesn't match ecu_serial is ignored") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val sEcu = sign(keys, ecuMan)
    val otherEcu = GenEcuSerial.generate

    val devMan = DeviceManifest(primEcu, Map( otherEcu -> sEcu.asJson))
    val sdevMan = sign(keys, devMan)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus shouldBe Seq()
  }

  test("erroneous signed ecu manifest is ignored") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val wrongKeys = generateKey
    val sEcu = sign(wrongKeys, ecuMan)

    val devMan = DeviceManifest(primEcu, Map( primEcu -> sEcu.asJson))
    val sdevMan = sign(keys, devMan)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus shouldBe Seq()
  }

  test("erroneous signed ecu manifest is ignored (legacy device manifest)") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val wrongKeys = generateKey
    val sEcu = sign(wrongKeys, ecuMan)

    val devMan = LegacyDeviceManifest(primEcu, Seq(sEcu))
    val sdevMan = sign(keys, devMan)

    val vEcus = Verify.legacyDeviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus shouldBe Seq()
  }
}

class EdVerifySpec extends VerifySpec {
  val keytype = EdKeyType
  val keySize = 256 // keySize doesn't matter
}

class RsaVerifySpec extends VerifySpec {
  val keytype = RsaKeyType
  val keySize = 2048
}
