package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.SignatureMethod._
import com.advancedtelematic.director.data.GeneratorOps._
import java.time.Instant
import org.scalacheck.Gen


trait Generators {
  lazy val GenHexChar: Gen[Char] = Gen.oneOf(('0' to '9') ++ ('a' to 'f'))

  lazy val GenHexString: Gen[HexString] = GenRefinedStringByChar(GenHexChar)

  lazy val GenEcuSerial: Gen[EcuSerial]
    = Gen.choose(10,64).flatMap(GenRefinedStringByCharN(_, Gen.alphaChar))

  lazy val GenSignatureMethod: Gen[SignatureMethod]
    = Gen.const(RSASSA_PSS)

  lazy val GenCrypto: Gen[Crypto] = for {
    method <- GenSignatureMethod
    pubKey <- Gen.identifier
  } yield Crypto(method, pubKey)

  lazy val GenRegisterEcu: Gen[RegisterEcu] = for {
    ecu <- GenEcuSerial
    crypto <- GenCrypto
  } yield RegisterEcu(ecu, crypto)

  lazy val GenKeyId: Gen[KeyId]= GenRefinedStringByCharN(64, GenHexChar)

  lazy val GenClientSignature: Gen[ClientSignature] = for {
    method <- GenSignatureMethod
    sig <- GenHexString
    keyid <- GenKeyId
  } yield ClientSignature(method, sig, keyid)

  def GenSignedValue[T](value: T): Gen[SignedPayload[T]] = for {
    signature <- Gen.nonEmptyContainerOf[List, ClientSignature](GenClientSignature)
  } yield SignedPayload(signature, value)

  def GenSigned[T](genT: Gen[T]): Gen[SignedPayload[T]] =
    genT.flatMap(GenSignedValue)


  lazy val GenSha256: Gen[Sha256] = GenRefinedStringByCharN(64, GenHexChar)
  lazy val GenSha512: Gen[Sha512] = GenRefinedStringByCharN(128, GenHexChar)

  lazy val GenHashes: Gen[Hashes] = for {
    sha256 <- GenSha256
    sha512 <- GenSha512
  } yield Hashes(sha256, sha512)

  lazy val GenFileInfo: Gen[FileInfo] = for {
    hs <- GenHashes
    len <- Gen.posNum[Int]
  } yield FileInfo(hs, len)

  lazy val GenInstalledImage: Gen[InstalledImage] = for {
    fp <- Gen.alphaStr
    fi <- GenFileInfo
  } yield InstalledImage(fp, fi)

  def GenEcuManifest(ecuSerial: EcuSerial): Gen[EcuManifest] =  for {
    time <- Gen.const(Instant.now)
    image <- GenInstalledImage
    ptime <- Gen.const(Instant.now)
    attacks <- Gen.alphaStr
  } yield EcuManifest(time, image, ptime, ecuSerial, attacks)
}
