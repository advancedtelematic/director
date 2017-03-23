package com.advancedtelematic.director.http

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKitBase}
import com.advancedtelematic.director.daemon.CreateRepoActor
import com.advancedtelematic.director.db.{RepoNameRepositorySupport, RootFilesRepositorySupport}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, FakeRoleStore}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.TufDataType.RepoId
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Milliseconds, Seconds, Span}

import scala.concurrent.ExecutionContext.Implicits.global

class RegisterNamespaceSpec extends DirectorSpec
  with BeforeAndAfterAll
  with Eventually
  with DatabaseSpec
  with DefaultPatience
  with TestKitBase
  with RepoNameRepositorySupport
  with RootFilesRepositorySupport {

  implicit lazy val system = ActorSystem(this.getClass.getSimpleName)

  override def afterAll(): Unit = {
    super.afterAll
    system.terminate()
  }

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  test("creates root repository and root file for namespace") {
    val testActorRef = TestActorRef(new CreateRepoActor(FakeRoleStore))

    val namespace = Namespace("defaultNS")

    testActorRef ! UserCreated(namespace.get)

    eventually(timeout, interval) {
      val repoId = repoNameRepository.getRepo(namespace).futureValue
      repoId shouldBe a[RepoId]

      val rootFile = rootFilesRepository.find(namespace).futureValue
      rootFile.hcursor.downField("signed").downField("_type").as[String] shouldBe Right("Root")
    }
  }

  test("creating new namespace works if root.json is not available directly") {
    val testActorRef = TestActorRef(new CreateRepoActor(new KeyserverClientWithFailure(1)))
    val namespace = Namespace("namespace-for-late-root")
    testActorRef ! UserCreated(namespace.get)

    eventually(timeout, interval) {
      val repoId = repoNameRepository.getRepo(namespace).futureValue
      repoId shouldBe a[RepoId]
    }
  }

}

class KeyserverClientWithFailure(nrOfTries: Int) extends KeyserverClient {
  import io.circe.{Decoder, Encoder, Json}
  import com.advancedtelematic.libtuf.data.TufDataType._
  import scala.concurrent.Future

  import RoleType.RoleType

  var remaining = nrOfTries

  override def createRoot(repoId: RepoId): Future[Json] = Future.successful(Json.obj())
  override def sign[T : Decoder : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = ???
  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[Json]] = if (remaining <= 0) {
    Future.successful(SignedPayload(List(), Json.obj()))
  } else {
    remaining = remaining - 1
    Future.failed(RootRoleNotFound)
  }
}
