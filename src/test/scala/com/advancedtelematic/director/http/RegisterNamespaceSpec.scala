package com.advancedtelematic.director.http

import akka.actor.ActorSystem
import com.advancedtelematic.director.data.DataType.Namespace
import com.advancedtelematic.director.db.{RepoNameRepositorySupport, RootFilesRepositorySupport}
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, FakeRoleStore}
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
    with RepoNameRepositorySupport
    with RootFilesRepositorySupport {

  implicit lazy val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)

  override def afterAll(): Unit = {
    super.afterAll
    system.terminate()
  }

  private val timeout = Timeout(Span(5, Seconds))
  private val interval = Interval(Span(200, Milliseconds))

  test("creating new namespace") {
    val namespace = Namespace("defaultNS")

    val repoId = RegisterNamespace.action(FakeRoleStore, namespace).futureValue
    val repoId2 = repoNameRepository.getRepo(namespace).futureValue
    repoId2 shouldBe repoId

    val rootFile = rootFilesRepository.find(namespace).futureValue

    rootFile.hcursor.downField("signed").downField("_type").as[String].toEither shouldBe Right("Root")

  }

  class KeyserverClientWithFailure(nrOfTries: Int) extends KeyserverClient {
    import io.circe.{Decoder, Encoder, Json}
    import com.advancedtelematic.libtuf.data.TufDataType._
    import scala.concurrent.Future

    import RoleType.RoleType

    var remaing = nrOfTries

    override def createRoot(repoId: RepoId): Future[Json] = Future.successful(Json.obj())
    override def sign[T : Decoder : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = ???
    override def fetchRootRole(repoId: RepoId): Future[SignedPayload[Json]] = if (remaing <= 0) {
      Future.successful(SignedPayload(List(), Json.obj()))
    } else {
      remaing = remaing - 1
      Future.failed(RootRoleNotFound)
    }
  }

  test("creating new namespace works if root.json is not available directly") {
    val namespace = Namespace("namespace-for-late-root")
    val repoId = RepoId.generate

    val ref = system.actorOf(RootFileListener.props(namespace, new KeyserverClientWithFailure(1), repoId))

    eventually(timeout, interval) {
      val repoId2 = repoNameRepository.getRepo(namespace).futureValue
      repoId2 shouldBe repoId
    }
  }

}
