package com.advancedtelematic.director.data

import cats.syntax.show._
import com.advancedtelematic.director.data.DataType.MultiTargetUpdate
import com.advancedtelematic.director.data.DataType.UpdateId._
import com.advancedtelematic.libats.messaging.Messages.MessageLike
import com.advancedtelematic.libtuf.data.TufCodecs._

object Messages {
  lazy implicit val multiTargetUpdateCreatedMessageLike: MessageLike[MultiTargetUpdate] =
    MessageLike[MultiTargetUpdate](_.id.show)
}
