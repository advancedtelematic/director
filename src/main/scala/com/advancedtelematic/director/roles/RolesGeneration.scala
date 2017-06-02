package com.advancedtelematic.director.roles

import com.advancedtelematic.director.data.DataType.{CustomImage, FileCacheRequest}
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.libats.codecs.AkkaCirce.refinedEncoder
import com.advancedtelematic.libats.messaging_datatype.DataType.EcuSerial
import com.advancedtelematic.libtuf.crypt.CanonicalJson.ToCanonicalJsonOps
import com.advancedtelematic.libtuf.crypt.Sha256Digest
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, MetaItem, RoleTypeToMetaPathOp, SnapshotRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RoleType, SignedPayload}
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import io.circe.{Encoder, Json}
import io.circe.syntax._
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class RolesGeneration(tuf: KeyserverClient)
                     (implicit val db: Database, ec: ExecutionContext) extends AdminRepositorySupport
    with FileCacheRepositorySupport
    with RepoNameRepositorySupport {

  private def metaItem[T : Encoder](content: T): MetaItem = {
    val file = content.asJson.canonical.getBytes
    val checkSum = Sha256Digest.digest(file)

    MetaItem(Map(checkSum.method -> checkSum.hash), file.length)
  }

  def targetsRole(targets: Map[EcuSerial, CustomImage], targetVersion: Int, expires: Instant): TargetsRole = {
    val clientsTarget = targets.map { case (ecu_serial, image) =>
      image.filepath -> ClientTargetItem(image.fileinfo.hashes, image.fileinfo.length,
                                         Some(Json.obj("ecuIdentifier" -> ecu_serial.asJson,
                                                       "uri" -> image.uri.asJson)))
    }

    TargetsRole(expires = expires,
                targets = clientsTarget,
                version = targetVersion)
  }

  def snapshotRole(targetsRole: SignedPayload[TargetsRole], version: Int, expires: Instant): SnapshotRole =
    SnapshotRole(meta = Map(RoleType.TARGETS.toMetaPath -> metaItem(targetsRole)),
                 expires = expires,
                 version = version)

  def timestampRole(snapshotRole: SignedPayload[SnapshotRole], version: Int, expires: Instant): TimestampRole =
    TimestampRole(meta = Map(RoleType.SNAPSHOT.toMetaPath -> metaItem(snapshotRole)),
                  expires = expires,
                  version = version)

  def generateFiles(fcr: FileCacheRequest): Future[Unit] = for {
    repo <- repoNameRepository.getRepo(fcr.namespace)

    targets <- adminRepository.fetchTargetVersion(fcr.namespace, fcr.device, fcr.targetVersion)

    expires = Instant.now.plus(31, ChronoUnit.DAYS)
    targetsRole   <- tuf.sign(repo, RoleType.TARGETS, targetsRole(targets, fcr.targetVersion, expires))
    snapshotRole  <- tuf.sign(repo, RoleType.SNAPSHOT, snapshotRole(targetsRole, fcr.targetVersion, expires))
    timestampRole <- tuf.sign(repo, RoleType.TIMESTAMP, timestampRole(snapshotRole, fcr.timestampVersion, expires))

    _ <- fileCacheRepository.storeJson(fcr.device, fcr.timestampVersion, expires, targetsRole, snapshotRole, timestampRole)
  } yield ()

  def processFileCacheRequest(fcr: FileCacheRequest): Future[Unit] = for {
    _ <- generateFiles(fcr)
    _ <- adminRepository.updateDeviceTargets(fcr.device, fcr.updateId, fcr.timestampVersion)
  } yield ()
}
