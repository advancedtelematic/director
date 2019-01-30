package com.advancedtelematic.director.manifest

import java.time.Instant
import java.time.temporal.ChronoUnit

import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.Ecu
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifestEcuSigned, EcuManifest}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Legacy.LegacyDeviceManifest
import com.advancedtelematic.director.data.TestCodecs._
import com.advancedtelematic.director.data.{EdGenerators, KeyGenerators, RsaGenerators}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, KeyType, RsaKeyType, SignedPayload, TufKeyPair}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

abstract class VerifySpec
    extends DirectorSpec
    with KeyGenerators
    with DefaultPatience
{
  val keytype: KeyType
  val keySize: Int

  def generateKey: TufKeyPair = {
    TufCrypto.generateKeyPair(keytype, keySize)
  }

  def sign[T : Encoder : Decoder](key: TufKeyPair, payload: T): SignedPayload[T] = {
    val signature = TufCrypto
      .signPayload(key.privkey, payload.asJson)
      .toClient(key.pubkey.id)

    SignedPayload(List(signature), payload, payload.asJson)
  }

  val namespace = Namespace("verify-spec")

  def generateKeyAndEcuManifest: (TufKeyPair, Ecu, EcuIdentifier, EcuManifest) = {
    val deviceId = DeviceId.generate
    val keys = generateKey

    val primEcu = GenEcuIdentifier.generate

    val ecu = Ecu(primEcu, deviceId, namespace, true, GenHardwareIdentifier.generate, keys.pubkey)

    val time = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val pretime = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val ecuMan = GenEcuManifest(primEcu).generate.copy(timeserver_time = time,
                                                       previous_timeserver_time = pretime)

    (keys, ecu, primEcu, ecuMan)
  }

  test("correctly verifies correct signature for device manifest") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val sEcu = sign(keys, ecuMan)

    val devMan = DeviceManifestEcuSigned(primEcu, Map( primEcu -> sEcu.asJson))
    val sdevMan = sign(keys, devMan.asJson)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus.ecu_manifests shouldBe Seq(ecuMan)
  }

  test("correctly verifies correct signature for legacy device manifest") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val sEcu = sign(keys, ecuMan)

    val devMan = LegacyDeviceManifest(primEcu, Seq(sEcu))
    val sdevMan = sign(keys, devMan.asJson)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus.ecu_manifests shouldBe Seq(ecuMan)
  }

  test("can still verify device-manifest with extra fields") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val sEcu = sign(keys, ecuMan)

    val devMan = DeviceManifestEcuSigned(primEcu, Map( primEcu -> sEcu.asJson))
    val jsonToSend = devMan.asJson.mapObject(_.add("extra-field", Json.fromString("extra content here")))
    val sdevMan = sign(keys, jsonToSend)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus.ecu_manifests shouldBe Seq(ecuMan)
  }

  test("can still verify with ecu-manifest with extra fields") {
    val (keys, ecu, primEcu, ecuMan) = generateKeyAndEcuManifest
    val jsonEcuMan = ecuMan.asJson.hcursor.downField("installed_image").downField("fileinfo").downField("hashes")
      .withFocus(_.mapObject(_.add("sha512", Json.fromString("sha512 comes here")))).top.get
    val sEcu = sign(keys, jsonEcuMan)

    val devMan = DeviceManifestEcuSigned(primEcu, Map( primEcu -> sEcu.asJson))
    val sdevMan = sign(keys, devMan.asJson)

    val vEcus = Verify.deviceManifest(Seq(ecu), SignatureVerification.verify, sdevMan).get

    vEcus.ecu_manifests shouldBe Seq(ecuMan)
  }
}

class EdVerifySpec extends VerifySpec with EdGenerators {
  val keytype = Ed25519KeyType
  val keySize = 256 // keySize doesn't matter
}

class RsaVerifySpec extends VerifySpec with RsaGenerators {
  val keytype = RsaKeyType
  val keySize = 2048
}
