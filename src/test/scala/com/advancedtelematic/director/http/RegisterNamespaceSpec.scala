package com.advancedtelematic.director.http

import com.advancedtelematic.director.daemon.CreateRepoWorker
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}

class RegisterNamespaceSpec extends DirectorSpec
    with Eventually
    with DatabaseSpec
    with DefaultPatience
    with NamespacedRequests
    with ResourceSpec
    with RepoNameRepositorySupport {

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  test("creates root repository and root file for namespace") {
    val createRepoWorker = new CreateRepoWorker(new DirectorRepo(keyserverClient))
    val namespace = Namespace("defaultNS")

    createRepoWorker.action(UserCreated(namespace.get))

    eventually(timeout, interval) {
      val repoId = repoNameRepository.getRepo(namespace).futureValue
      repoId shouldBe a[RepoId]

      val rootFile = keyserverClient.fetchRootRole(repoId).futureValue
      rootFile.signed.hcursor.downField("_type").as[String] shouldBe Right("Root")
    }
  }

  testWithNamespace("create repo via end-point") { implicit ns =>
    val repoId = createRepo
    fetchRootOk
  }
}
