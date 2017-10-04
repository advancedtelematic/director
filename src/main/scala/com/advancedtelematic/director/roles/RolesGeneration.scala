package com.advancedtelematic.director.roles

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.diff_service.client.DiffServiceClient
import com.advancedtelematic.director.data.Codecs.encoderTargetCustom
import com.advancedtelematic.director.data.DataType.{CustomImage, FileCacheRequest, Image, TargetCustom, TargetCustomImage, TargetUpdate}
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport,
  MultiTargetUpdatesRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{Checksum, DeviceId, EcuSerial, HashMethod}
import com.advancedtelematic.libtuf.crypt.CanonicalJson.ToCanonicalJsonOps
import com.advancedtelematic.libtuf.crypt.Sha256Digest
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, MetaItem, RoleTypeToMetaPathOp, SnapshotRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RoleType, SignedPayload}
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import io.circe.Encoder
import io.circe.syntax._
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

object RolesGeneration {
  case object MtuDiffDataMissing extends Throwable
}

class RolesGeneration(tuf: KeyserverClient, diffService: DiffServiceClient)
                     (implicit val db: Database, ec: ExecutionContext) extends AdminRepositorySupport
    with FileCacheRepositorySupport
    with MultiTargetUpdatesRepositorySupport
    with RepoNameRepositorySupport {

  private def metaItem[T : Encoder](version: Int, content: T): MetaItem = {
    val file = content.asJson.canonical.getBytes
    val checkSum = Sha256Digest.digest(file)

    MetaItem(Map(checkSum.method -> checkSum.hash), file.length, version = version)
  }

  def targetsRole(targets: Map[EcuSerial, TargetCustomImage], targetVersion: Int, expires: Instant): TargetsRole = {
    val clientsTarget = targets.map { case (ecu_serial, TargetCustomImage(image, hardware, uri, diff)) =>
      val targetCustom = TargetCustom(ecu_serial, hardware, uri, diff)

      image.filepath -> ClientTargetItem(image.fileinfo.hashes.toClientHashes, image.fileinfo.length,
                                         Some(targetCustom.asJson))
    }

    TargetsRole(expires = expires,
                targets = clientsTarget,
                version = targetVersion)
  }

  def snapshotRole(targetsRole: SignedPayload[TargetsRole], version: Int, expires: Instant): SnapshotRole =
    SnapshotRole(meta = Map(RoleType.TARGETS.toMetaPath -> metaItem(version, targetsRole)),
                 expires = expires,
                 version = version)

  def timestampRole(snapshotRole: SignedPayload[SnapshotRole], version: Int, expires: Instant): TimestampRole =
    TimestampRole(meta = Map(RoleType.SNAPSHOT.toMetaPath -> metaItem(version, snapshotRole)),
                  expires = expires,
                  version = version)


  def generateWithCustom(namespace: Namespace, device: DeviceId, targetVersion: Int, timestampVersion: Int, targets: Map[EcuSerial, TargetCustomImage]): Future[Done] = for {
    repo <- repoNameRepository.getRepo(namespace)

    expires = Instant.now.plus(31, ChronoUnit.DAYS)
    targetsRole   <- tuf.sign(repo, RoleType.TARGETS, targetsRole(targets, targetVersion, expires))
    snapshotRole  <- tuf.sign(repo, RoleType.SNAPSHOT, snapshotRole(targetsRole, targetVersion, expires))
    timestampRole <- tuf.sign(repo, RoleType.TIMESTAMP, timestampRole(snapshotRole, timestampVersion, expires))

    _ <- fileCacheRepository.storeJson(device, timestampVersion, expires, targetsRole, snapshotRole, timestampRole)
  } yield Done

  private def fromImage(image: Image): TargetUpdate = {
    val checksum = Checksum(HashMethod.SHA256, image.fileinfo.hashes.sha256)
    TargetUpdate(image.filepath, checksum, image.fileinfo.length)
  }

  def generateCustomTargets(ns: Namespace, device: DeviceId, currentImages: Map[EcuSerial, Image],
                            targets: Map[EcuSerial, (HardwareIdentifier, CustomImage)]): Future[Map[EcuSerial, TargetCustomImage]] =
    Future.traverse(targets) { case (ecu, (hw, CustomImage(image, uri, doDiff))) =>
      doDiff match {
        case None => FastFuture.successful(ecu -> TargetCustomImage(image, hw, uri, None))
        case Some(targetFormat) =>
          val from = fromImage(currentImages(ecu))
          val to = fromImage(image)
          diffService.findDiffInfo(ns, targetFormat, from, to).flatMap {
            case None => FastFuture.failed(MtuDiffDataMissing)
            case Some(diffInfo) => FastFuture.successful(ecu -> TargetCustomImage(image, hw, uri, Some(diffInfo)))
          }
      }
    }.map(_.toMap)

  def tryToGenerate(namespace: Namespace, device: DeviceId, targetVersion: Int, timestampVersion: Int): Future[Done] = for {
    targets <- adminRepository.fetchCustomTargetVersion(namespace, device, targetVersion)
    currentImages <- adminRepository.findImages(namespace, device)
    customTargets <- generateCustomTargets(namespace, device, currentImages.toMap, targets)
    _ <- generateWithCustom(namespace, device, targetVersion, timestampVersion, customTargets)
  } yield Done

  def processFileCacheRequest(fcr: FileCacheRequest): Future[Unit] = for {
    _ <- tryToGenerate(fcr.namespace, fcr.device, fcr.targetVersion, fcr.timestampVersion)
  } yield ()
}
