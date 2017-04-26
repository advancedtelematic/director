package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.RSA
import com.advancedtelematic.libtuf.data.TufDataType.ValidSignature
import com.advancedtelematic.libtuf.crypt.RsaKeyPair.{generate, sign}
import org.bouncycastle.util.encoders.Base64

import scala.util.Success

class SignatureVerificationSpec extends DirectorSpec {
  import SignatureVerification.verify

  test("can verify correct signature") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    val sig = sign(keys.getPrivate, data)
    val clientKey = ClientKey(RSA, keys.getPublic)

    verify(clientKey)(sig, data) shouldBe Success(true)
  }

  test("reject signature from different message") {
    val keys = generate(size = 1024)
    val data1 = "0123456789abcdef".getBytes
    val data2 = "0123456789abcdfe".getBytes

    val sig = sign(keys.getPrivate, data1)
    val clientKey = ClientKey(RSA, keys.getPublic)

    verify(clientKey)(sig, data2) shouldBe Success(false)
  }

  test("reject changed signature from valid") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    def updateBit(base64: String): String = {
      var bytes = Base64.decode(base64.getBytes)
      bytes(0) = (bytes(0) ^ 1).toByte
      new String(Base64.encode(bytes))
    }

    val sig = {
      val orig = sign(keys.getPrivate, data)
      val newSig = updateBit(orig.sig.value).refineTry[ValidSignature].get
      orig.copy(sig = newSig)
    }
    val clientKey = ClientKey(RSA, keys.getPublic)

    verify(clientKey)(sig, data) shouldBe Success(false)
  }

}
