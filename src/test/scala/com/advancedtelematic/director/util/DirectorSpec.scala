package com.advancedtelematic.director.util

import com.advancedtelematic.director.util.NamespaceTag.{NamespaceTag, withRandomNamespace}
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, Matchers, Tag}
import org.scalactic.source.Position

abstract class DirectorSpec extends FunSuite
    with Matchers
    with ScalaFutures {

  Security.addProvider(new BouncyCastleProvider())

  def testWithNamespace(testName: String, testArgs: Tag*)(testFun: NamespaceTag => Any)
                       (implicit pos: Position): Unit = {
    test(testName, testArgs :_*)(withRandomNamespace(testFun))(pos = pos)
  }
}
