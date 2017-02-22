package com.advancedtelematic.director.data

import com.advancedtelematic.libats.data.RefinedUtils._
import eu.timepit.refined.api.{Refined, Validate}
import org.scalacheck.Gen
import scala.annotation.tailrec

object GeneratorOps {
  implicit class GenSample[T](gen: Gen[T]) {
    @tailrec
    final def generate: T =
      gen.sample match {
        case Some(v) => v
        case None => generate
      }
  }

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
