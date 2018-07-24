package com.advancedtelematic.director.repo

import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libtuf.data.ClientDataType.RootRole
import com.advancedtelematic.libtuf.data.TufDataType
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, JsonSignedPayload, KeyId, KeyType, RepoId, SignedPayload}
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import io.circe.{Decoder, Encoder, Json}

import scala.concurrent.{ExecutionContext, Future}

class FakeKeyserverClient(implicit ec: ExecutionContext) extends KeyserverClient {
  var count: Int = 0

  override def createRoot(repoId: RepoId, keyType: KeyType): Future[Json] = Future {
    count += 1
    Json.obj()
  }

  override def sign(repoId: RepoId, roleType: RoleType, payload: Json): Future[JsonSignedPayload] = ???

  override def fetchUnsignedRoot(repoId: RepoId): Future[RootRole] = ???

  override def updateRoot(repoId: RepoId, signedPayload: SignedPayload[RootRole]): Future[Unit] = ???

  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[RootRole]] = ???

  override def fetchRootRole(repoId: RepoId, version: Int): Future[SignedPayload[RootRole]] = ???

  override def deletePrivateKey(repoId: RepoId, keyId: KeyId): Future[Unit] = ???

  override def fetchKeyPair(repoId: RepoId, keyId: KeyId): Future[TufDataType.TufKeyPair] = ???

  override def fetchTargetKeyPairs(repoId: RepoId): Future[Seq[TufDataType.TufKeyPair]] = ???
}

class DirectorRepoSpec
    extends DirectorSpec
    with DefaultPatience
    with ResourceSpec
{

  val dirNs = Namespace("director-repo-spec")

  val countKeyserverClient = new FakeKeyserverClient()
  val directorRepo = new DirectorRepo(countKeyserverClient)

  test("Only create repo once") {
    countKeyserverClient.count shouldBe 0
    val repoId = directorRepo.findOrCreate(dirNs, Ed25519KeyType).futureValue
    countKeyserverClient.count shouldBe 1
    val repoId2 = directorRepo.findOrCreate(dirNs, Ed25519KeyType).futureValue

    countKeyserverClient.count shouldBe 1
    repoId shouldBe repoId2
  }

}
