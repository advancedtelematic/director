package com.advancedtelematic.director.manifest

import cats.Show
import cats.syntax.show._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.RefinedUtils._
import com.advancedtelematic.director.data.SignatureMethod._
import com.advancedtelematic.director.util.DirectorSpec

import java.security.{KeyPair, KeyPairGenerator, PublicKey, PrivateKey, SecureRandom}

import org.bouncycastle.util.encoders.Hex
import scala.util.Success

object SignatureVerificationSpec {
  // make this deterministic for test
  def generate(size: Int = 512): KeyPair = {
    val keyGen = KeyPairGenerator.getInstance("RSA", "BC")
    keyGen.initialize(size, new SecureRandom(seed))
    keyGen.generateKeyPair()
  }


  def sign(privateKey: PrivateKey, data: Array[Byte]): Signature = {
    val signer = java.security.Signature.getInstance("SHA256withRSAandMGF1", "BC") // RSASSA-PSS
    signer.initSign(privateKey)
    signer.update(data)
    val signature = signer.sign()
    val hexSignature = Hex.toHexString(signature).refineTry[ValidHexString].get
    Signature(hexSignature, SignatureMethod.RSASSA_PSS)
  }

  implicit def keyShow[T <: java.security.Key]: Show[T] = Show.show { key ⇒
    val pemStrWriter = new StringWriter()
    val jcaPEMWriter = new JcaPEMWriter(pemStrWriter)
    jcaPEMWriter.writeObject(key)
    jcaPEMWriter.flush()
    pemStrWriter.toString
  }
}

class SignatureVerificationSpec extends DirectorSpec {
  import SignatureVerification._
  test("can verify correct signature") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    val sig = sign(keys.getPrivate, data)
    val crypto = Crypto(RSASSA_PSS, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(true)
  }

  test("rejects completly spoofed signature") {
    val keys = generate(size = 1024)
    val data = "0123456789abcdef".getBytes

    val sig = Signature(Hex.toHexString(data).refineTry[ValidHexString].get, RSASSA_PSS)
    val crypto = Crypto(RSASSA_PSS, keys.getPublic.show)

    verify(crypto)(sig, data) shouldBe Success(false)
  }

  test("reject changed signature from valid") {
    val keys = generate(size = 1024)
    val data1 = "0123456789abcdef".getBytes
    val data2 = "0123456789abcdfe".getBytes

    val sig = sign(keys.getPrivate, data1)
    val crypto = Crypto(RSASSA_PSS, keys.getPublic.show)

    verify(crypto)(sig, data2) shouldBe Success(false)
  }

}
