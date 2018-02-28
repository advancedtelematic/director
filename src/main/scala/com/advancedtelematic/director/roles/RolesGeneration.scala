package com.advancedtelematic.director.roles

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.diff_service.client.DiffServiceClient
import com.advancedtelematic.director.data.Codecs.encoderTargetCustom
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport, MultiTargetUpdatesRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.data.DataType.{Checksum, HashMethod, Namespace}
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libtuf.crypt.CanonicalJson.ToCanonicalJsonOps
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, MetaItem, RoleTypeToMetaPathOp, SnapshotRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RoleType, SignedPayload, TargetFilename, ValidTargetFilename}
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import io.circe.Encoder
import io.circe.syntax._
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

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
    // there can be multiple ECUs per filename
    val byFilename: Map[TargetFilename, Map[EcuSerial, TargetCustomImage]] = targets.groupBy {
      case (_, TargetCustomImage(image, _, _, _)) => image.filepath.value.refineTry[ValidTargetFilename].get
    }

    val clientTargetItems = byFilename.mapValues { ecuImageMap =>
      val targetCustomUris = ecuImageMap.mapValues {
        case TargetCustomImage(_, hardwareId, uri, diff) => TargetCustomUri(hardwareId, uri, diff)
      }

      val (ecu_serial, TargetCustomImage(Image(_, fileInfo), hardwareId1, uri1, diff1)) = ecuImageMap.head
      val targetCustom = TargetCustom(ecu_serial, hardwareId1, uri1, diff1, targetCustomUris)

      ClientTargetItem(fileInfo.hashes.toClientHashes, fileInfo.length, Some(targetCustom.asJson))
    }

    TargetsRole(expires, clientTargetItems, targetVersion)
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
