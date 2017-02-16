package com.advancedtelematic.director.manifest

import com.advancedtelematic.director.data.DataType.Crypto
import com.advancedtelematic.libtuf.data.TufDataType.Signature
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.{KeyType, RSA}
import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod.{SignatureMethod, RSASSA_PSS}
import com.advancedtelematic.libtuf.crypt.RsaKeyPair.{isValid, parsePublic}

import scala.util.{Failure, Try}

object SignatureVerification {
  def keyTypeMatchSignature(kt: KeyType, sm: SignatureMethod): Boolean = (kt, sm) match {
    case (RSA, RSASSA_PSS) => true
  }

  def verify(crypto: Crypto)(sig: Signature, data: Array[Byte]): Try[Boolean] = {
    if (!keyTypeMatchSignature(crypto.method, sig.method)) {
      Failure(Errors.SignatureMethodMismatch)
    } else {
      parsePublic(crypto.publicKey).map{ pubKey =>
        isValid(pubKey, sig, data)
      }
    }
  }
}
