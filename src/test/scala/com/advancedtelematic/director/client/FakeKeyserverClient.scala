package com.advancedtelematic.director.client

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.crypt.TufCrypto.PublicKeyOps
import com.advancedtelematic.libtuf.data.ClientDataType.{RoleKeys, RootRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufDataType._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import io.circe.{Decoder, Encoder, Json}
import java.security.{KeyPair, PublicKey}
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

object FakeKeyserverClient extends KeyserverClient {

  import scala.concurrent.ExecutionContext.Implicits.global
  import io.circe.syntax._

  private val keys = new ConcurrentHashMap[RepoId, Map[RoleType, KeyPair]]()

  private val rootRoles = new ConcurrentHashMap[RepoId, RootRole]()

  def publicKey(repoId: RepoId, roleType: RoleType): PublicKey = keys.get(repoId)(roleType).getPublic

  private lazy val preGeneratedKeys = RoleType.ALL.map { role =>
    val (publicKey, privateKey) = TufCrypto.generateKeyPair(RsaKeyType, 2048)
    role -> new KeyPair(publicKey.keyval, privateKey.keyval)
  }.toMap

  private def generateRoot(repoId: RepoId): RootRole = {
    val roles = keys.get(repoId).map { case (role, keyPair) =>
      role -> RoleKeys(List(keyPair.getPublic.id), threshold = 1)
    }

    val clientKeys = keys.get(repoId).map { case (_, keyPair) =>
      keyPair.getPublic.id -> RSATufKey(keyPair.getPublic)
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
      FastFuture.failed(RootRoleConflict)
    } else {
      generateKeys(repoId)
      val rootRole = generateRoot(repoId)
      rootRoles.put(repoId, rootRole)
      FastFuture.successful(rootRole.asJson)
    }
  }

  override def sign[T: Decoder : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = {
    val key = Option(keys.get(repoId)).flatMap(_.get(roleType)).getOrElse(throw RoleKeyNotFound)
    val signature = TufCrypto.signPayload(RSATufPrivateKey(key.getPrivate), payload).toClient(key.getPublic.id)

    FastFuture.successful(SignedPayload(List(signature), payload))
  }

  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[RootRole]] =
    Future.fromTry {
      Try {
        rootRoles.asScala(repoId)
      }.recover {
        case _: NoSuchElementException => throw RootRoleNotFound
      }
    }.flatMap { role =>
      sign(repoId, RoleType.ROOT, role)
    }

  override def addTargetKey(repoId: RepoId, key: TufKey): Future[Unit] = {
    if(!rootRoles.containsKey(repoId))
      FastFuture.failed(RootRoleNotFound)
    else {
      rootRoles.computeIfPresent(repoId, (_: RepoId, role: RootRole) => {
        val newKeys = role.keys + (key.id -> key)
        val targetRoleKeys = role.roles(RoleType.TARGETS)
        val newTargetKeys = RoleKeys(targetRoleKeys.keyids :+ key.id, targetRoleKeys.threshold)

        role.copy(keys = newKeys, roles = role.roles + (RoleType.TARGETS -> newTargetKeys))
      })

      FastFuture.successful(())
    }
  }

  override def fetchUnsignedRoot(repoId: RepoId): Future[RootRole] = fetchRootRole(repoId).map(_.signed)

  override def updateRoot(repoId: RepoId, signedPayload: SignedPayload[RootRole]): Future[Unit] = FastFuture.successful {
    rootRoles.computeIfPresent(repoId, (t: RepoId, u: RootRole) => {
      assert(u != null, "fake keyserver, Role does not exist")
      signedPayload.signed
    })
  }

  override def deletePrivateKey(repoId: RepoId, keyId: KeyId): Future[TufPrivateKey] = FastFuture.successful {
    val keyPair = keys.asScala.get(repoId).flatMap(_.values.find(_.getPublic.id == keyId)).getOrElse(throw RoleKeyNotFound)

    keys.computeIfPresent(repoId, (id: RepoId, existingKeys: Map[RoleType, KeyPair]) => {
      existingKeys.filter(_._2.getPublic.id != keyId)
    })

    RSATufPrivateKey(keyPair.getPrivate)
  }
}
