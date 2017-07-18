package com.advancedtelematic.director.repo

import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libtuf.data.TufDataType.{KeyType, RepoId, SignedPayload}
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import io.circe.{Decoder, Encoder, Json}
import scala.concurrent.{ExecutionContext, Future}

class FakeKeyserverClient(implicit ec: ExecutionContext) extends KeyserverClient {
  var count: Int = 0

  override def createRoot(repoId: RepoId, keyType: KeyType): Future[Json] = Future {
    count += 1
    Json.obj()
  }

  override def sign[T : Decoder : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = ???

  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[Json]] = ???

}

class DirectorRepoSpec
    extends DirectorSpec
    with DefaultPatience
    with ResourceSpec
{

  val dirNs = Namespace("director-repo-spec")

  val keyserverClient = new FakeKeyserverClient()
  val directorRepo = new DirectorRepo(keyserverClient)

  test("Only create repo once") {
    keyserverClient.count shouldBe 0
    val repoId = directorRepo.findOrCreate(dirNs).futureValue
    keyserverClient.count shouldBe 1
    val repoId2 = directorRepo.findOrCreate(dirNs).futureValue

    keyserverClient.count shouldBe 1
    repoId shouldBe repoId2
  }

}
