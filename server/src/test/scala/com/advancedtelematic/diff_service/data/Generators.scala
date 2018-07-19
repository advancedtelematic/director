package com.advancedtelematic.diff_service.data

import com.advancedtelematic.diff_service.data.DataType._
import com.advancedtelematic.director.data.DataType.TargetUpdate
import com.advancedtelematic.libats.data.DataType.{Checksum, HashMethod, ValidChecksum}
import com.advancedtelematic.libtuf.data.TufDataType.ValidTargetFilename
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat._
import eu.timepit.refined.api.Refined
import org.scalacheck.Gen

trait Generators {
  lazy val GenHexChar: Gen[Char] = Gen.oneOf(('0' to '9') ++ ('a' to 'f'))

  def genIdentifier(maxLen: Int): Gen[String] = for {
    //use a minimum length of 10 to reduce possibility of naming conflicts
    size <- Gen.choose(10, maxLen)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield name.mkString

  lazy val GenTargetFormat: Gen[TargetFormat] =
    Gen.oneOf(BINARY, OSTREE)

  lazy val GenChecksum: Gen[Checksum] = for {
    hash <- Gen.containerOfN[Seq, Char](64, GenHexChar).map { x =>
      Refined.unsafeApply[String, ValidChecksum](x.mkString)
    }
  } yield Checksum(HashMethod.SHA256, hash)

  lazy val GenTargetUpdate: Gen[TargetUpdate] = for {
    target <- genIdentifier(200).map(Refined.unsafeApply[String, ValidTargetFilename])
    size <- Gen.chooseNum(0, Long.MaxValue)
    checksum <- GenChecksum
  } yield TargetUpdate(target, checksum, size)

  def GenCreateDiffInfoRequest(format: TargetFormat): Gen[CreateDiffInfoRequest] = for {
    from <- GenTargetUpdate
    to <- GenTargetUpdate
  } yield CreateDiffInfoRequest(format, from, to)

}
