package com.advancedtelematic.director.db

import java.io.StringWriter
import java.security.PublicKey

import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libats.data.DataType.HashMethod
import com.advancedtelematic.libats.data.DataType.HashMethod.HashMethod
import com.advancedtelematic.libtuf.crypt.TufCrypto
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import slick.jdbc.MySQLProfile.api._

object SlickMapping {
  import com.advancedtelematic.libats.slick.codecs.SlickEnumMapper
  import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat

  implicit val hashMethodColumn = MappedColumnType.base[HashMethod, String](_.toString, HashMethod.withName)
  implicit val targetFormatMapper = SlickEnumMapper.enumMapper(TargetFormat)

  implicit val publicKeyMapper = MappedColumnType.base[PublicKey, String](
    { publicKey => {
      val pemStrWriter = new StringWriter()
      val jcaPEMWriter = new JcaPEMWriter(pemStrWriter)
      jcaPEMWriter.writeObject(publicKey)
      jcaPEMWriter.flush()
      pemStrWriter.toString
    } },
    {str => TufCrypto.parsePublicPem(str).get}
  )

  implicit val fileCacheRequestStatusMapper = SlickEnumMapper.enumMapper(FileCacheRequestStatus)
}
