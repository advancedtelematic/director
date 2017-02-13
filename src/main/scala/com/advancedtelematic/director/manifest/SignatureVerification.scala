package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.SignatureMethod

import java.io.StringReader
import java.security.PublicKey

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.util.encoders.Hex
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.PEMParser

import scala.util.{Failure, Try}

object SignatureVerification {
  def isValid(publicKey: PublicKey, signature: Signature, data: Array[Byte]): Boolean = {
    if (signature.method != SignatureMethod.RSASSA_PSS)
      throw new IllegalArgumentException(s"Signature method not supported: ${signature.method}")

    val signer = java.security.Signature.getInstance("SHA256withRSAandMGF1", "BC") // RSASSA-PSS
    val hexDecodedSignature = Hex.decode(signature.sig.get)
    signer.initVerify(publicKey)
    signer.update(data)
    signer.verify(hexDecodedSignature)
  }

  def parsePublic(pubKey: String): Try[PublicKey] = {
    val parser = new PEMParser(new StringReader(pubKey))
    val converter = new JcaPEMKeyConverter()
    Try{

    val pemKeyPair = parser.readObject().asInstanceOf[SubjectPublicKeyInfo]
    converter.getPublicKey(pemKeyPair)
    }
  }

  def verify(crypto: Crypto)(sig: Signature, data: Array[Byte]): Try[Boolean] = {
    if (crypto.method != sig.method) {
      Failure(Errors.SignatureMethodMismatch)
    } else {
      parsePublic(crypto.publicKey).map{ pubKey =>
        isValid(pubKey, sig, data)
      }
    }
  }
}
