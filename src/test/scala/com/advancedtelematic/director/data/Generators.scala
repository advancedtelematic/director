package com.advancedtelematic.director.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.data.Legacy._
import com.advancedtelematic.director.data.TestCodecs._
import com.advancedtelematic.libats.data.DataType.{Checksum, HashMethod, ValidChecksum}
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuSerial, ValidTargetFilename}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType._
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.{BINARY, OSTREE, TargetFormat}
import io.circe.Encoder
import io.circe.syntax._
import java.time.Instant

import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod.SignatureMethod
import eu.timepit.refined.api.Refined
import org.scalacheck.Gen

trait Generators {
  lazy val GenHexChar: Gen[Char] = Gen.oneOf(('0' to '9') ++ ('a' to 'f'))

  lazy val GenEcuSerial: Gen[EcuSerial] =
    Gen.choose(10, 64).flatMap(GenRefinedStringByCharN(_, Gen.alphaChar))

  lazy val GenHardwareIdentifier: Gen[HardwareIdentifier] =
    Gen.choose(10, 200).flatMap(GenRefinedStringByCharN(_, Gen.alphaChar))

  lazy val GenTargetFormat: Gen[TargetFormat] =
    Gen.oneOf(BINARY, OSTREE)

  lazy val GenDiffInfo: Gen[DiffInfo] = for {
    ch <- GenChecksum
    size <- Gen.posNum[Long]
    uri = Uri("http://example.com/fetch/delta")
  } yield DiffInfo(ch, size, uri)

  lazy val GenKeyId: Gen[KeyId] = GenRefinedStringByCharN(64, GenHexChar)

  lazy val GenHashes: Gen[Hashes] = for {
    hash <- GenRefinedStringByCharN[ValidChecksum](64, GenHexChar)
  } yield Hashes(hash)

  lazy val GenFileInfo: Gen[FileInfo] = for {
    hs <- GenHashes
    len <- Gen.posNum[Int]
  } yield FileInfo(hs, len)

  lazy val GenFileInfoInvalidHash: Gen[FileInfo] = for {
    hs <- Gen.const(Hashes(Refined.unsafeApply("0")))
    len <- Gen.posNum[Int]
  } yield FileInfo(hs, len)

  lazy val GenImage: Gen[Image] = for {
    fp <- Gen.alphaStr.suchThat(x => x.nonEmpty && x.length < 254).map(Refined.unsafeApply[String, ValidTargetFilename])
    fi <- GenFileInfo
  } yield Image(fp, fi)

  lazy val GenImageInvalidHash: Gen[Image] = for {
    fp <- Gen.alphaStr.suchThat(x => x.nonEmpty && x.length < 254).map(Refined.unsafeApply[String, ValidTargetFilename])
    fi <- GenFileInfoInvalidHash
  } yield Image(fp, fi)

  lazy val GenCustomImage: Gen[CustomImage] = for {
    im <- GenImage
    tf <- Gen.option(GenTargetFormat)
  } yield CustomImage(im, Uri("http://www.example.com"), tf)

  lazy val GenChecksum: Gen[Checksum] = for {
    hash <- GenRefinedStringByCharN[ValidChecksum](64, GenHexChar)
  } yield Checksum(HashMethod.SHA256, hash)

  def GenEcuManifestWithImage(ecuSerial: EcuSerial, image: Image, custom: Option[CustomManifest]): Gen[EcuManifest] = for {
    time <- Gen.const(Instant.now)
    ptime <- Gen.const(Instant.now)
    attacks <- Gen.alphaStr
  } yield EcuManifest(time, image, ptime, ecuSerial, attacks, custom = custom.map(_.asJson))

  def GenEcuManifest(ecuSerial: EcuSerial, custom: Option[CustomManifest] = None): Gen[EcuManifest] =
    GenImage.flatMap(GenEcuManifestWithImage(ecuSerial, _, custom))

  def genIdentifier(maxLen: Int): Gen[String] = for {
    //use a minimum length of 10 to reduce possibility of naming conflicts
    size <- Gen.choose(10, maxLen)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield name.mkString

  val GenTargetName: Gen[TargetName] = for {
    target <- genIdentifier(100)
  } yield TargetName(target)

  val GenTargetVersion: Gen[TargetVersion] = for {
    target <- genIdentifier(100)
  } yield TargetVersion(target)

  val GenTargetUpdate: Gen[TargetUpdate] = for {
    target <- genIdentifier(200).map(Refined.unsafeApply[String, ValidTargetFilename])
    size <- Gen.chooseNum(0, Long.MaxValue)
    checksum <- GenChecksum
  } yield TargetUpdate(target, checksum, size)

  val GenTargetUpdateRequest: Gen[TargetUpdateRequest] = for {
    targetUpdate <- GenTargetUpdate
  } yield TargetUpdateRequest(None, targetUpdate, OSTREE, false)

  val GenMultiTargetUpdateRequest: Gen[MultiTargetUpdateRequest] = for {
    targets <- Gen.mapOf(Gen.zip(GenHardwareIdentifier, GenTargetUpdateRequest))
  } yield MultiTargetUpdateRequest(targets)
}

trait KeyGenerators extends Generators {
  val testKeyType: KeyType
  val testSignatureMethod: SignatureMethod

  lazy val GenKeyType: Gen[KeyType]
    = Gen.const(testKeyType)

  lazy val GenSignatureMethod: Gen[SignatureMethod]
    = Gen.const(testSignatureMethod)

  lazy val GenTufKey: Gen[TufKey] = for {
    keyType <- GenKeyType
    keyPair = testKeyType.crypto.generateKeyPair
  } yield keyPair.pubkey

  lazy val GenRegisterEcu: Gen[RegisterEcu] = for {
    ecu <- GenEcuSerial
    hwId <- GenHardwareIdentifier
    crypto <- GenTufKey
  } yield RegisterEcu(ecu, hwId, crypto)

  lazy val GenClientSignature: Gen[ClientSignature] = for {
    keyid <- GenKeyId
    method <- GenSignatureMethod
    sig <- GenRefinedStringByCharN[ValidSignature](256, GenHexChar)
  } yield ClientSignature(keyid, method, sig)

  def GenSignedValue[T : Encoder](value: T): Gen[SignedPayload[T]] = for {
    nrSig <- Gen.choose(1,10)
    signature <- Gen.containerOfN[List, ClientSignature](nrSig, GenClientSignature)
  } yield SignedPayload(signature, value)

  def GenSigned[T : Encoder](genT: Gen[T]): Gen[SignedPayload[T]] =
    genT.flatMap(t => GenSignedValue(t))

  def GenSignedEcuManifestWithImage(ecuSerial: EcuSerial, image: Image, custom: Option[CustomManifest] = None): Gen[SignedPayload[EcuManifest]] =
    GenSigned(GenEcuManifestWithImage(ecuSerial, image, custom))

  def GenSignedEcuManifest(ecuSerial: EcuSerial, custom: Option[CustomManifest] = None): Gen[SignedPayload[EcuManifest]] =
    GenSigned(GenEcuManifest(ecuSerial, custom))

  def GenSignedDeviceManifest(primeEcu: EcuSerial, ecusManifests: Seq[SignedPayload[EcuManifest]]) =
    GenSignedValue(DeviceManifest(primeEcu, ecusManifests.map{ secuMan => secuMan.signed.ecu_serial -> secuMan.asJson}.toMap).asJson)

  def GenSignedDeviceManifest(primeEcu: EcuSerial, ecusManifests: Map[EcuSerial, SignedPayload[EcuManifest]]) =
    GenSignedValue(DeviceManifest(primeEcu, ecusManifests.map{case (k, v) => k -> v.asJson}).asJson)

  def GenSignedLegacyDeviceManifest(primeEcu: EcuSerial, ecusManifests: Seq[SignedPayload[EcuManifest]]) =
    GenSignedValue(LegacyDeviceManifest(primeEcu, ecusManifests))
}

trait RsaGenerators extends KeyGenerators {
  val testKeyType: KeyType = RsaKeyType
  val testSignatureMethod: SignatureMethod = SignatureMethod.RSASSA_PSS_SHA256
}

trait EdGenerators extends KeyGenerators {
  val testKeyType: KeyType = Ed25519KeyType
  val testSignatureMethod: SignatureMethod = SignatureMethod.ED25519
}
