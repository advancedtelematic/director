package com.advancedtelematic.director.data

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.libtuf.crypt.RsaKeyPair
import com.advancedtelematic.libtuf.data.TufDataType._
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientHashes, ClientKey}
import io.circe.Encoder
import io.circe.syntax._
import java.time.Instant
import org.scalacheck.Gen

trait Generators {
  import KeyType._
  import SignatureMethod._

  lazy val GenHexChar: Gen[Char] = Gen.oneOf(('0' to '9') ++ ('a' to 'f'))

  lazy val GenEcuSerial: Gen[EcuSerial]
    = Gen.choose(10,64).flatMap(GenRefinedStringByCharN(_, Gen.alphaChar))

  lazy val GenKeyType: Gen[KeyType]
    = Gen.const(RSA)

  lazy val GenSignatureMethod: Gen[SignatureMethod]
    = Gen.const(SignatureMethod.RSASSA_PSS)

  lazy val GenClientKey: Gen[ClientKey] = for {
    keyType <- GenKeyType
    pubKey = RsaKeyPair.generate(size = 128).getPublic
  } yield ClientKey(keyType, pubKey)

  lazy val GenHardwareIdentifier: Gen[HardwareIdentifier] =
    Gen.choose(10,200).flatMap(GenRefinedStringByCharN(_, Gen.alphaChar))

  lazy val GenRegisterEcu: Gen[RegisterEcu] = for {
    ecu <- GenEcuSerial
    hwId <- GenHardwareIdentifier
    crypto <- GenClientKey
  } yield RegisterEcu(ecu, hwId, crypto)

  lazy val GenKeyId: Gen[KeyId]= GenRefinedStringByCharN(64, GenHexChar)

  lazy val GenClientSignature: Gen[ClientSignature] = for {
    keyid <- GenKeyId
    method <- GenSignatureMethod
    sig <- GenRefinedStringByCharN[ValidSignature](256, GenHexChar)
  } yield ClientSignature(keyid, method, sig)

  def GenSignedValue[T : Encoder](value: T): Gen[SignedPayload[T]] = for {
    signature <- Gen.nonEmptyContainerOf[List, ClientSignature](GenClientSignature)
  } yield SignedPayload(signature, value)

  def GenSigned[T : Encoder](genT: Gen[T]): Gen[SignedPayload[T]] =
    genT.flatMap(t => GenSignedValue(t))

  lazy val GenHashes: Gen[ClientHashes] = for {
    hash <- GenRefinedStringByCharN[ValidChecksum](64, GenHexChar)
  } yield Map(HashMethod.SHA256 -> hash)

  lazy val GenFileInfo: Gen[FileInfo] = for {
    hs <- GenHashes
    len <- Gen.posNum[Int]
  } yield FileInfo(hs, len)

  lazy val GenImage: Gen[Image] = for {
    fp <- Gen.alphaStr
    fi <- GenFileInfo
  } yield Image(fp, fi)

  lazy val GenCustomImage: Gen[CustomImage] = for {
    im <- GenImage
  } yield CustomImage(im.filepath, im.fileinfo, Uri("http://www.example.com"))

  lazy val GenChecksum: Gen[Checksum] = for {
    hash <- GenRefinedStringByCharN[ValidChecksum](64, GenHexChar)
  } yield Checksum(HashMethod.SHA256, hash)

  def GenEcuManifestWithImage(ecuSerial: EcuSerial, image: Image, custom: Option[CustomManifest]): Gen[EcuManifest] =  for {
    time <- Gen.const(Instant.now)
    ptime <- Gen.const(Instant.now)
    attacks <- Gen.alphaStr
  } yield EcuManifest(time, image, ptime, ecuSerial, attacks, custom = custom.map(_.asJson))

  def GenEcuManifest(ecuSerial: EcuSerial, custom: Option[CustomManifest] = None): Gen[EcuManifest] =
    GenImage.flatMap(GenEcuManifestWithImage(ecuSerial, _, custom))

  def GenSignedEcuManifestWithImage(ecuSerial: EcuSerial, image: Image, custom: Option[CustomManifest] = None): Gen[SignedPayload[EcuManifest]] =
    GenSigned(GenEcuManifestWithImage(ecuSerial, image, custom))
  def GenSignedEcuManifest(ecuSerial: EcuSerial, custom: Option[CustomManifest] = None): Gen[SignedPayload[EcuManifest]] = GenSigned(GenEcuManifest(ecuSerial, custom))
  def GenSignedDeviceManifest(primeEcu: EcuSerial, ecusManifests: Seq[SignedPayload[EcuManifest]]) = GenSignedValue(DeviceManifest(primeEcu, ecusManifests))

  def genIdentifier(maxLen: Int): Gen[String] = for {
  //use a minimum length of 10 to reduce possibility of naming conflicts
    size <- Gen.choose(10, maxLen)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield name.mkString

  val GenMultiTargetUpdateRequest: Gen[MultiTargetUpdateRequest] = for {
    hardwareId <- GenHardwareIdentifier
    target <- genIdentifier(200)
    size <- Gen.chooseNum(0, Long.MaxValue)
    checksum <- GenChecksum
  } yield MultiTargetUpdateRequest(UpdateId.generate, hardwareId, target, checksum, size)
}
