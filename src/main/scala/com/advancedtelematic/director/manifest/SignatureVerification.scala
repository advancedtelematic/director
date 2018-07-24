package com.advancedtelematic.director.manifest

import com.advancedtelematic.libtuf.data.TufDataType.{Signature, TufKey}
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import java.security.SignatureException
import org.slf4j.LoggerFactory

import scala.util.Try

object SignatureVerification {
  val log = LoggerFactory.getLogger(this.getClass)

  def verify(clientKey: TufKey)(sig: Signature, data: Array[Byte]): Try[Boolean] = {
    if(log.isDebugEnabled) {
      log.debug(s"Verifying signature '${sig.sig.value}' of data '${Sha256Digest.digest(data)}' using key '${Sha256Digest.digest(clientKey.keyval.getEncoded)}'")
    }

    Try {
      TufCrypto.isValid(sig, clientKey.keyval, data)
    }.recover {
      case x: SignatureException => false
      case TufCrypto.SignatureMethodMismatch(from, to) => false
    }
  }
}
