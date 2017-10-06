package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.data.TufDataType.{EdKeyType, KeyType, RsaKeyType, ValidSignature}
import com.advancedtelematic.libtuf.crypt.TufCrypto
import io.circe.syntax._
import org.bouncycastle.util.encoders.Base64
import scala.util.Success

abstract class SignatureVerificationSpec extends DirectorSpec {
  import SignatureVerification.verify

  val keytype: KeyType
  val keySize: Int

  test("can verify correct signature") {
    val (pub, sec) = TufCrypto.generateKeyPair(keytype, keySize = keySize)
    val data = "0123456789abcdef"

    val sig = TufCrypto.signPayload(sec, data)

    verify(pub)(sig, data.asJson.noSpaces.getBytes) shouldBe Success(true)
  }

  test("reject signature from different message") {
    val (pub, sec) = TufCrypto.generateKeyPair(keytype, keySize = keySize)
    val data1 = "0123456789abcdef"
    val data2 = "0123456789abcdfe"

    val sig = TufCrypto.signPayload(sec, data1)

    verify(pub)(sig, data2.asJson.noSpaces.getBytes) shouldBe Success(false)
  }

  test("reject changed signature from valid") {
    val (pub, sec) = TufCrypto.generateKeyPair(keytype, keySize = keySize)
    val data = "0123456789abcdef"

    def updateBit(base64: String): String = {
      val bytes = Base64.decode(base64.getBytes)
      bytes(0) = (bytes(0) ^ 1).toByte
      new String(Base64.encode(bytes))
    }

    val sig = {
      val orig = TufCrypto.signPayload(sec, data)
      val newSig = updateBit(orig.sig.value).refineTry[ValidSignature].get
      orig.copy(sig = newSig)
    }

    verify(pub)(sig, data.asJson.noSpaces.getBytes) shouldBe Success(false)
  }
}

class EdSignatureVerificationSpec extends SignatureVerificationSpec {
  val keytype = EdKeyType
  val keySize = 128 // keySize doesn't matter for EdKeyType
}

class RsaSignatureVerificationSpec extends SignatureVerificationSpec {
  val keytype = RsaKeyType
  val keySize = 2048
}
