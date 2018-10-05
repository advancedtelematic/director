package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.FileCacheRequestStatus
import com.advancedtelematic.director.data.LaunchedMultiTargetUpdateStatus
import com.advancedtelematic.libats.slick.codecs.SlickEnumMapper

object SlickMapping {
  implicit val fileCacheRequestStatusMapper = SlickEnumMapper.enumMapper(FileCacheRequestStatus)
  implicit val launchedMultiTargetUpdateStatusMapper = SlickEnumMapper.enumMapper(LaunchedMultiTargetUpdateStatus)
}

