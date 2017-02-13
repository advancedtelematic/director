package com.advancedtelematic.director.db

import slick.driver.MySQLDriver.api._
import scala.reflect.ClassTag

object SlickCirceMapper {
  import io.circe.Json
  import io.circe.{Encoder, Decoder}
  import io.circe.parser.decode
  import io.circe.syntax._

  private def circeMapper[T : Encoder : Decoder : ClassTag] = MappedColumnType.base[T, String](
    _.asJson.noSpaces,
    str => decode(str).valueOr(throw _)
  )

  implicit val jsonMapper = circeMapper[Json]
}
