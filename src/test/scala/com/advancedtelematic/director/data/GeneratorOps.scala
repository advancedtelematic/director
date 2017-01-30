package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.RefinedUtils._
import eu.timepit.refined.api.{Refined, Validate}
import org.scalacheck.Gen

object GeneratorOps {
  implicit class GenRefine[T](gen: Gen[T]) {
    final def refine[P](implicit ev: Validate[T,P]): Gen[Refined[T,P]] =
      gen.map(_.refineTry.get)
  }
}
