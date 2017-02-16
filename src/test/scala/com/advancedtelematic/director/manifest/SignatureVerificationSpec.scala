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

    val sig = {
      val orig = sign(keys.getPrivate, data)
      val origHex = orig.hex.get
      val newHead = if (origHex.head == 'f') '0'
                    else if (origHex.head == '0') 'a'
                    else (origHex.head + 1).toChar
      val newHex = (newHead +: origHex.tail).refineTry[ValidSignature].get
      orig.copy(hex = newHex)
    }
    val crypto = Crypto(RSA, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(false)
  }

}
