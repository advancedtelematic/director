package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.data.RefinedUtils._
import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.RSA
import com.advancedtelematic.libtuf.data.TufDataType.ValidSignature
import com.advancedtelematic.libtuf.crypt.RsaKeyPair.{generate, sign}

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

    // We change only one bit so lets just increment it like gray code
    // https://en.wikipedia.org/wiki/Gray_code
    val grayCodedChar = {
      val seq = "01326754cdfeab980"
      seq.zip(seq.tail).toMap
    }

    val sig = {
      val orig = sign(keys.getPrivate, data)
      val origHex = orig.hex.get
      val newHex = (grayCodedChar(origHex.head) +: origHex.tail).refineTry[ValidSignature].get
      orig.copy(hex = newHex)
    }
    val clientKey = ClientKey(RSA, keys.getPublic)

    verify(clientKey)(sig, data) shouldBe Success(false)
  }

}
