package com.advancedtelematic.diff_service.util

import com.advancedtelematic.diff_service.data.Generators
import com.advancedtelematic.director.util.NamespaceTag.{NamespaceTag, withRandomNamespace}
import org.scalactic.source.Position
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, Matchers, Tag}

abstract class DiffServiceSpec
    extends FunSuite
    with Generators
    with Matchers
    with ScalaFutures {

  def testWithNamespace(testName: String, testArgs: Tag*)(testFun: NamespaceTag => Any)
                       (implicit pos: Position): Unit = {
    test(testName, testArgs :_*)(withRandomNamespace(testFun))(pos = pos)
  }
}
