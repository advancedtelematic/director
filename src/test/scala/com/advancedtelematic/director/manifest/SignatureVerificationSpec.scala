package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, KeyType, RsaKeyType, ValidSignature}
import com.advancedtelematic.libtuf.crypt.TufCrypto
import io.circe.syntax._
import org.bouncycastle.util.encoders.Base64

import scala.util.Success

abstract class SignatureVerificationSpec extends DirectorSpec {
  import SignatureVerification.verify

  val keytype: KeyType

  test("can verify correct signature") {
    val keyPair = TufCrypto.generateKeyPair(keytype, keytype.crypto.defaultKeySize)
    val data = "0123456789abcdef"

    val sig = TufCrypto.signPayload(keyPair.privkey, data)

    verify(keyPair.pubkey)(sig, data.asJson.noSpaces.getBytes) shouldBe Success(true)
  }

  test("reject signature from different message") {
    val keyPair = TufCrypto.generateKeyPair(keytype, keytype.crypto.defaultKeySize)
    val data1 = "0123456789abcdef"
    val data2 = "0123456789abcdfe"

    val sig = TufCrypto.signPayload(keyPair.privkey, data1)

    verify(keyPair.pubkey)(sig, data2.asJson.noSpaces.getBytes) shouldBe Success(false)
  }

  test("reject changed signature from valid") {
    val keyPair = TufCrypto.generateKeyPair(keytype, keytype.crypto.defaultKeySize)
    val data = "0123456789abcdef"

    def updateBit(base64: String): String = {
      val bytes = Base64.decode(base64.getBytes)
      bytes(0) = (bytes(0) ^ 1).toByte
      new String(Base64.encode(bytes))
    }

    val sig = {
      val orig = TufCrypto.signPayload(keyPair.privkey, data)
      val newSig = updateBit(orig.sig.value).refineTry[ValidSignature].get
      orig.copy(sig = newSig)
    }

    verify(keyPair.pubkey)(sig, data.asJson.noSpaces.getBytes) shouldBe Success(false)
  }
}

class EdSignatureVerificationSpec extends SignatureVerificationSpec {
  val keytype = Ed25519KeyType
}

class RsaSignatureVerificationSpec extends SignatureVerificationSpec {
  val keytype = RsaKeyType
}
