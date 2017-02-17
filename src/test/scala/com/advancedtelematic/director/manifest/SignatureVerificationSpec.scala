package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.RefinedUtils._
import com.advancedtelematic.director.util.DirectorSpec
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.RSA
import com.advancedtelematic.libtuf.data.TufDataType.ValidSignature
import com.advancedtelematic.libtuf.crypt.RsaKeyPair.{generate, sign, keyShow}

import scala.util.Success

class SignatureVerificationSpec extends DirectorSpec {
  import SignatureVerification.verify

  test("can verify correct signature") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    val sig = sign(keys.getPrivate, data)
    val crypto = Crypto(RSA, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(true)
  }

  test("reject signature from different message") {
    val keys = generate(size = 1024)
    val data1 = "0123456789abcdef".getBytes
    val data2 = "0123456789abcdfe".getBytes

    val sig = sign(keys.getPrivate, data1)
    val crypto = Crypto(RSA, keys.getPublic.show)

    verify(crypto)(sig, data2) shouldBe Success(false)
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
    val crypto = Crypto(RSA, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(false)
  }

}
