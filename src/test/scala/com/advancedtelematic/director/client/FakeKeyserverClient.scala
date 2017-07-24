package com.advancedtelematic.director.client

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libtuf.crypt.CanonicalJson.ToCanonicalJsonOps
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.crypt.TufCrypto.PublicKeyOps
import com.advancedtelematic.libtuf.data.ClientDataType.{RoleKeys, RootRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufDataType._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax._
import java.security.{KeyPair, PublicKey}
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

object FakeKeyserverClient extends KeyserverClient {

  private val keys = new ConcurrentHashMap[RepoId, KeyPair]()

  def publicKey(repoId: RepoId): PublicKey =
    keys.asScala(repoId).getPublic

  private def keyPair(repoId: RepoId): KeyPair =
    keys.asScala(repoId)

  def rootRole(repoId: RepoId) = {
    val rootKey = keys.asScala(repoId)
    val clientKeys = Map(rootKey.getPublic.id -> RSATufKey(rootKey.getPublic))

    val roles = RoleType.ALL.map { role =>
      role -> RoleKeys(List(rootKey.getPublic.id), threshold = 1)
    }.toMap

    RootRole(clientKeys, roles, expires = Instant.now.plusSeconds(3600), version = 1)
  }

  def generateKey(repoId: RepoId): KeyPair = {
    val (pub, sec) = TufCrypto.generateKeyPair(RsaKeyType, 2048)
    keys.put(repoId, new KeyPair(pub.keyval, sec.keyval))
  }

  override def createRoot(repoId: RepoId, keyType: KeyType): Future[Json] = {
    if (keys.contains(repoId)) {
      FastFuture.failed(RootRoleConflict)
    } else {
      generateKey(repoId)
      FastFuture.successful(Json.obj())
    }
  }

  override def sign[T : Decoder : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = {
    val signature = signWithRoot(repoId, payload)
    FastFuture.successful(SignedPayload(List(signature), payload))
  }

  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[Json]] = {
    Future.fromTry {
      Try {
        val role = rootRole(repoId)
        val signature = signWithRoot(repoId, role)
        SignedPayload(List(signature), role.asJson)
      }.recover {
        case ex: NoSuchElementException =>
          throw RootRoleNotFound
      }
    }
  }

  private def signWithRoot[T : Encoder](repoId: RepoId, payload: T): ClientSignature = {
    val key = keyPair(repoId)
    TufCrypto
      .sign(RsaKeyType, key.getPrivate, payload.asJson.canonical.getBytes)
      .toClient(key.getPublic.id)
  }
}
