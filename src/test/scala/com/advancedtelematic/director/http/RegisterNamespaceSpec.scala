package com.advancedtelematic.director.http

import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import com.advancedtelematic.director.daemon.CreateRepoWorker
import com.advancedtelematic.director.db.RepoNameRepositorySupport
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, FakeRoleStore, ResourceSpec}
import com.advancedtelematic.director.util.NamespaceTag._
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.RootRole
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, SignedPayload}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}

class RegisterNamespaceSpec extends DirectorSpec
    with Eventually
    with DatabaseSpec
    with DefaultPatience
    with ResourceSpec
    with RepoNameRepositorySupport {

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  def createRepo(implicit ns: NamespaceTag): RepoId =
    Post(apiUri("admin/repo")).namespaced ~> routes ~> check  {
      status shouldBe StatusCodes.Created
      responseAs[RepoId]
    }

  def fetchRoot(implicit ns: NamespaceTag): HttpRequest =
    Get(apiUri("admin/root.json")).namespaced

  def fetchRootOk(implicit ns: NamespaceTag): SignedPayload[RootRole] =
    fetchRoot ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[RootRole]]
    }

  test("creates root repository and root file for namespace") {
    val createRepoWorker = new CreateRepoWorker(new DirectorRepo(FakeRoleStore))
    val namespace = Namespace("defaultNS")

    createRepoWorker.action(UserCreated(namespace.get))

    eventually(timeout, interval) {
      val repoId = repoNameRepository.getRepo(namespace).futureValue
      repoId shouldBe a[RepoId]

      val rootFile = FakeRoleStore.fetchRootRole(repoId).futureValue
      rootFile.signed.hcursor.downField("_type").as[String] shouldBe Right("Root")
    }
  }

  test("create repo via end-point") {
    withRandomNamespace { implicit ns =>
      val repoId = createRepo
      fetchRootOk
    }
  }
}
