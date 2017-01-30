package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.RefinedUtils._
import com.advancedtelematic.director.data.SignatureMethod._
import com.advancedtelematic.director.util.DirectorSpec

import org.bouncycastle.util.encoders.Hex
import scala.util.Success

class SignatureVerificationSpec extends DirectorSpec {
  import SignatureVerification._

  test("can verify correct signature") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    val sig = sign(keys.getPrivate, data)
    val crypto = Crypto(RSASSA_PSS, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(true)
  }

  test("rejects spoofed signature") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    val sig = Signature(Hex.toHexString(data).refineTry[ValidHexString].get, RSASSA_PSS)
    val crypto = Crypto(RSASSA_PSS, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(false)
  }
}
