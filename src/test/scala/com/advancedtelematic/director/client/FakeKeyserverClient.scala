package com.advancedtelematic.director.client

import java.security.{KeyPair, PublicKey}
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.Generators
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.crypt.TufCrypto.PublicKeyOps
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{RoleKeys, RootRole}
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.{KeyId, KeyType, RepoId, RoleType, SignedPayload, TufKey, TufKeyPair}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient.{KeyPairNotFound, RoleKeyNotFound}
import io.circe.{Decoder, Encoder, Json}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try
import KeyserverClient._

class FakeKeyserverClient(defaultKeyType: KeyType) extends KeyserverClient with Generators {

  import io.circe.syntax._

  import scala.concurrent.ExecutionContext.Implicits.global

  private val keys = new ConcurrentHashMap[RepoId, Map[RoleType, KeyPair]]()

  private val rootRoles = new ConcurrentHashMap[RepoId, RootRole]()

  def publicKey(repoId: RepoId, roleType: RoleType): PublicKey = keys.get(repoId)(roleType).getPublic

  private lazy val preGeneratedKeys = RoleType.ALL.map { role =>
    val keyPair = TufCrypto.generateKeyPair(defaultKeyType, defaultKeyType.crypto.defaultKeySize)
    role -> new KeyPair(keyPair.pubkey.keyval, keyPair.privkey.keyval)
  }.toMap

  private def generateRoot(repoId: RepoId): RootRole = {
    val roles = keys.get(repoId).map { case (role, keyPair) =>
      role -> RoleKeys(List(keyPair.getPublic.id), threshold = 1)
    }

    val clientKeys = keys.get(repoId).map { case (_, keyPair) =>
      keyPair.getPublic.id -> defaultKeyType.crypto.convertPublic(keyPair.getPublic)
    }

    RootRole(clientKeys, roles, expires = Instant.now.plusSeconds(3600), version = 1)
  }

  private def generateKeys(repoId: RepoId): List[KeyPair] = {
    preGeneratedKeys.map { case (role, keyPair) =>
      keys.compute(repoId, (t: RepoId, u: Map[RoleType, KeyPair]) => {
        if (u == null)
          Map(role -> keyPair)
        else
          u + (role -> keyPair)
      })

      keyPair
    }
  }.toList

  def deleteRepo(repoId: RepoId): Option[RootRole] =
    Option(keys.remove(repoId)).flatMap(_ => Option(rootRoles.remove(repoId)))

  override def createRoot(repoId: RepoId, keyType: KeyType): Future[Json] = {
    if (keys.contains(repoId)) {
      FastFuture.failed(KeyserverClient.RootRoleConflict)
    } else {
      generateKeys(repoId)
      val rootRole = generateRoot(repoId)
      rootRoles.put(repoId, rootRole)
      FastFuture.successful(rootRole.asJson)
    }
  }

  override def sign[T: Decoder : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = {
    val key = Option(keys.get(repoId)).flatMap(_.get(roleType)).getOrElse(throw KeyserverClient.RoleKeyNotFound)
    val signature = TufCrypto.signPayload(defaultKeyType.crypto.convertPrivate(key.getPrivate), payload).toClient(key.getPublic.id)

    FastFuture.successful(SignedPayload(List(signature), payload))
  }

  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[RootRole]] =
    Future.fromTry {
      Try {
        rootRoles.asScala(repoId)
      }.recover {
        case _: NoSuchElementException => throw KeyserverClient.RootRoleNotFound
      }
    }.flatMap { role =>
      sign(repoId, RoleType.ROOT, role)
    }

  override def fetchUnsignedRoot(repoId: RepoId): Future[RootRole] = fetchRootRole(repoId).map(_.signed)

  override def updateRoot(repoId: RepoId, signedPayload: SignedPayload[RootRole]): Future[Unit] = FastFuture.successful {
    rootRoles.computeIfPresent(repoId, (t: RepoId, u: RootRole) => {
      assert(u != null, "fake keyserver, Role does not exist")
      signedPayload.signed
    })
  }

  override def deletePrivateKey(repoId: RepoId, keyId: KeyId): Future[Unit] = FastFuture.successful {
    keys.computeIfPresent(repoId, (id: RepoId, existingKeys: Map[RoleType, KeyPair]) => {
      existingKeys.filter(_._2.getPublic.id != keyId)
    })
  }

  override def fetchTargetKeyPairs(repoId: RepoId): Future[Seq[TufKeyPair]] =  FastFuture.successful {
    val keyPair = keys.asScala.getOrElse(repoId, throw RoleKeyNotFound).getOrElse(RoleType.TARGETS, throw RoleKeyNotFound)

    Seq(defaultKeyType.crypto.toKeyPair(defaultKeyType.crypto.convertPublic(keyPair.getPublic),
      defaultKeyType.crypto.convertPrivate(keyPair.getPrivate)))
  }

  override def fetchRootRole(repoId: RepoId, version: Int): Future[SignedPayload[RootRole]] =
    fetchRootRole(repoId).filter(_.signed.version == version)

  override def fetchKeyPair(repoId: RepoId, keyId: KeyId): Future[TufKeyPair] = Future.fromTry {
    Try {
      val keyPair = keys.asScala.getOrElse(repoId, throw KeyPairNotFound).values.find(_.getPublic.id == keyId).getOrElse(throw KeyPairNotFound)
      val pb = defaultKeyType.crypto.convertPublic(keyPair.getPublic)
      val prv = defaultKeyType.crypto.convertPrivate(keyPair.getPrivate)
      defaultKeyType.crypto.toKeyPair(pb, prv)
    }
  }
}
