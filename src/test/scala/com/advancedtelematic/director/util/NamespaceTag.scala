package com.advancedtelematic.director.util

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.RawHeader
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.libats.data.DataType.Namespace
import org.scalacheck.Gen

object NamespaceTag {
  trait NamespaceTag {
    val value: String
    def get: Namespace = Namespace(value)
  }

  def withNamespace[T](ns: String)(fn: NamespaceTag => T): T =
    fn(new NamespaceTag { val value = ns })

  def withRandomNamespace[T](fn: NamespaceTag => T): T =
    fn(new NamespaceTag {
         val value = Gen.alphaChar.listBetween(100,150).generate.mkString
       })

  implicit class Namespaced(value: HttpRequest) {
    def namespaced(implicit namespaceTag: NamespaceTag): HttpRequest =
      value.addHeader(RawHeader("x-ats-namespace", namespaceTag.value))
  }
}
