package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest._
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.libtuf.data.TufDataType._
import com.advancedtelematic.libtuf.data.ClientDataType.ClientHashes
import io.circe.Encoder
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

  lazy val GenCrypto: Gen[Crypto] = for {
    keyType <- GenKeyType
    pubKey <- Gen.identifier
  } yield Crypto(keyType, pubKey)

  lazy val GenRegisterEcu: Gen[RegisterEcu] = for {
    ecu <- GenEcuSerial
    crypto <- GenCrypto
  } yield RegisterEcu(ecu, crypto)

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

  def GenEcuManifest(ecuSerial: EcuSerial): Gen[EcuManifest] =  for {
    time <- Gen.const(Instant.now)
    image <- GenImage
    ptime <- Gen.const(Instant.now)
    attacks <- Gen.alphaStr
  } yield EcuManifest(time, image, ptime, ecuSerial, attacks)

  def GenSignedEcuManifest(ecuSerial: EcuSerial): Gen[SignedPayload[EcuManifest]] = GenSigned(GenEcuManifest(ecuSerial))
  def GenSignedDeviceManifest(device: DeviceId, primeEcu: EcuSerial, ecusManifests: Seq[SignedPayload[EcuManifest]]) = GenSignedValue(DeviceManifest(device, primeEcu, ecusManifests))
}
