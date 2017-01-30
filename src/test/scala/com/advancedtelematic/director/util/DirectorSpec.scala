package com.advancedtelematic.director.util

import com.advancedtelematic.director.data.Generators
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, Matchers}

abstract class DirectorSpec extends FunSuite
    with Generators
    with Matchers
    with ScalaFutures {

  Security.addProvider(new BouncyCastleProvider())

}
