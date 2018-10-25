package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.libats.slick.codecs.SlickEnumMapper

object SlickMapping {
  implicit val fileCacheRequestStatusMapper = SlickEnumMapper.enumMapper(FileCacheRequestStatus)
}

