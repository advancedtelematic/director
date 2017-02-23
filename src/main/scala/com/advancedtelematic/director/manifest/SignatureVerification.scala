package com.advancedtelematic.director.manifest

import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.Signature
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.{KeyType, RSA}
import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod.{SignatureMethod, RSASSA_PSS}
import com.advancedtelematic.libtuf.crypt.RsaKeyPair.isValid

import scala.util.{Failure, Success, Try}

object SignatureVerification {
  def keyTypeMatchSignature(kt: KeyType, sm: SignatureMethod): Boolean = (kt, sm) match {
    case (RSA, RSASSA_PSS) => true
  }

  def verify(clientKey: ClientKey)(sig: Signature, data: Array[Byte]): Try[Boolean] = {
    if (!keyTypeMatchSignature(clientKey.keytype, sig.method)) {
      Failure(Errors.SignatureMethodMismatch)
    } else {
      Success(isValid(clientKey.keyval, sig, data))
    }
  }
}
