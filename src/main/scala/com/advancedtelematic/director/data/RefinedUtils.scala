package com.advancedtelematic.director.data

import eu.timepit.refined
import eu.timepit.refined.api.{Refined, Validate}
import org.genivi.sota.marshalling.RefinementError

import scala.util.{Failure, Success, Try}

object RefinedUtils {
  // Like this, lets put it in libats
  implicit class RefineTry[T](value: T) {
    def refineTry[P](implicit ev: Validate[T, P]): Try[Refined[T, P]] = {
      refined.refineV(value) match {
        case Left(err) => Failure(RefinementError(value, err))
        case Right(v) => Success(v)
      }
    }
  }
}
