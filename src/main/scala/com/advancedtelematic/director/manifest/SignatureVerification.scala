package com.advancedtelematic.director.manifest

import com.advancedtelematic.libtuf.data.ClientDataType.ClientKey
import com.advancedtelematic.libtuf.data.TufDataType.Signature
import com.advancedtelematic.libtuf.data.TufDataType.KeyType.{KeyType, RSA}
import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod.{RSASSA_PSS, SignatureMethod}
import com.advancedtelematic.libtuf.crypt.RsaKeyPair.isValid
import com.advancedtelematic.libtuf.crypt.Sha256Digest
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object SignatureVerification {
  def keyTypeMatchSignature(kt: KeyType, sm: SignatureMethod): Boolean = (kt, sm) match {
    case (RSA, RSASSA_PSS) => true
  }

  val log = LoggerFactory.getLogger(this.getClass)

  def verify(clientKey: ClientKey)(sig: Signature, data: Array[Byte]): Try[Boolean] = {
    if(log.isDebugEnabled) {
      log.debug(s"Verifying signature '${sig.sig.value}' of data '${Sha256Digest.digest(data)}' using key '${Sha256Digest.digest(clientKey.keyval.getEncoded)}'")
    }
    if (!keyTypeMatchSignature(clientKey.keytype, sig.method)) {
      Failure(Errors.SignatureMethodMismatch)
    } else {
      Success(isValid(clientKey.keyval, sig, data))
    }
  }
}
