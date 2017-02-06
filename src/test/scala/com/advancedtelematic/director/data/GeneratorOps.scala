package com.advancedtelematic.director.data

import com.advancedtelematic.director.data.RefinedUtils._
import eu.timepit.refined.api.{Refined, Validate}
import org.scalacheck.Gen

object GeneratorOps {
  implicit class GenRefine[T](gen: Gen[T]) {
    final def refine[P](implicit ev: Validate[T,P]): Gen[Refined[T,P]] =
      gen.map(_.refineTry.get)
  }

  def GenStringByChar(gen: Gen[Char]) =
    Gen.containerOf[List, Char](gen).map(_.mkString)

  def GenStringByCharN(len: Int, gen: Gen[Char]) =
    Gen.containerOfN[List, Char](len, gen).map(_.mkString)

  def GenRefinedStringByChar[P](gen: Gen[Char])(implicit ev: Validate[String, P])
    = GenStringByChar(gen).refine

  def GenRefinedStringByCharN[P](len: Int, gen: Gen[Char])(implicit ev: Validate[String, P])
    = GenStringByCharN(len, gen).refine
}
