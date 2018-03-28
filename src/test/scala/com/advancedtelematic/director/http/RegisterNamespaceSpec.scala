package com.advancedtelematic.director.http

import com.advancedtelematic.director.daemon.CreateRepoWorker
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, RouteResourceSpec}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, RepoId, RsaKeyType}
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}
import scala.util.Try

trait RegisterNamespaceSpec extends DirectorSpec
    with Eventually
    with DatabaseSpec
    with DefaultPatience
    with NamespacedRequests
    with RouteResourceSpec
    with RepoNameRepositorySupport {

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  test("creates root repository and root file for namespace") {
    val createRepoWorker = new CreateRepoWorker(new DirectorRepo(keyserverClient), defaultKeyType.get)
    val namespace = Namespace("defaultNS")

    createRepoWorker.action(UserCreated(namespace.get))

    eventually(timeout, interval) {
      val repoId = repoNameRepository.getRepo(namespace).futureValue
      repoId shouldBe a[RepoId]

      val rootFile = keyserverClient.fetchRootRole(repoId).futureValue
      rootFile.signed._type shouldBe "Root"
      rootFile.signed.keys.head._2.keytype shouldBe defaultKeyType.get
    }
  }

  testWithNamespace("create repo via end-point") { implicit ns =>
    createRepoOk(defaultKeyType.get)
  }
}

class RsaRegisterNamespaceSpec extends {
  override val defaultKeyType = Try(RsaKeyType)
} with RegisterNamespaceSpec

class EdRegisterNamespaceSpec extends {
  override val defaultKeyType = Try(Ed25519KeyType)
} with RegisterNamespaceSpec
