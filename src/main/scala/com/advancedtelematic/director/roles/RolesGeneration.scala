package com.advancedtelematic.director.roles

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.diff_service.client.DiffServiceClient
import com.advancedtelematic.director.data.Codecs.{encoderTargetCustom, targetsCustomEncoder}
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport, MultiTargetUpdatesRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.director.roles.RolesGeneration.MtuDiffDataMissing
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.{Checksum, CorrelationId, HashMethod, Namespace}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.crypt.CanonicalJson.ToCanonicalJsonOps
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, MetaItem, RoleTypeOps, SnapshotRole, TargetsRole, TimestampRole}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RepoId, RoleType, SignedPayload, TargetFilename, ValidTargetFilename}
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

  private def targetsRole(targets: Map[EcuIdentifier, TargetCustomImage], targetVersion: Int, expires: Instant,
                          custom: Option[TargetsCustom]): TargetsRole = {
    // there can be multiple ECUs per filename
    val byFilename: Map[TargetFilename, Map[EcuIdentifier, TargetCustomImage]] = targets.groupBy {
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

    TargetsRole(expires, clientTargetItems, targetVersion, custom = custom.map(_.asJson))
  }

  private def snapshotRole(targetsRole: SignedPayload[TargetsRole], version: Int, expires: Instant): SnapshotRole =
    SnapshotRole(meta = Map(RoleType.TARGETS.metaPath -> metaItem(version, targetsRole)),
                 expires = expires,
                 version = version)

  private def timestampRole(snapshotRole: SignedPayload[SnapshotRole], version: Int, expires: Instant): TimestampRole =
    TimestampRole(meta = Map(RoleType.SNAPSHOT.metaPath -> metaItem(version, snapshotRole)),
                  expires = expires,
                  version = version)

  private def signRole[T : Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[T]] = {
    tuf.sign(repoId, roleType, payload.asJson).map { signedJson ⇒
      SignedPayload(signedJson.signatures, payload, signedJson.signed)
    }
  }

  private def generateWithCustom(namespace: Namespace, device: DeviceId,
                                 targetVersion: Int, timestampVersion: Int,
                                 targets: Map[EcuIdentifier, TargetCustomImage],
                                 custom: Option[TargetsCustom]): Future[Done] = for {
    repo <- repoNameRepository.getRepo(namespace)

    expires = Instant.now.plus(31, ChronoUnit.DAYS)
    targetsRole   <- signRole(repo, RoleType.TARGETS, targetsRole(targets, targetVersion, expires, custom))
    snapshotRole  <- signRole(repo, RoleType.SNAPSHOT, snapshotRole(targetsRole, targetVersion, expires))
    timestampRole <- signRole(repo, RoleType.TIMESTAMP, timestampRole(snapshotRole, timestampVersion, expires))

    _ <- fileCacheRepository.storeJson(device, timestampVersion, expires, targetsRole, snapshotRole, timestampRole)
  } yield Done

  private def fromImage(image: Image, uri: Option[Uri]): TargetUpdate = {
    val checksum = Checksum(HashMethod.SHA256, image.fileinfo.hashes.sha256)
    TargetUpdate(image.filepath, checksum, image.fileinfo.length, uri)
  }

  // doesn't compile with the more concrete type Map instead of TraversableOnce, related to "-Ypartial-unification".
  // another  workaround might be to use parTraverse from cats
  private def generateCustomTargets(ns: Namespace, device: DeviceId, currentImages: Map[EcuIdentifier, Image],
                                    targets: TraversableOnce[(EcuIdentifier, (HardwareIdentifier, CustomImage))]): Future[Map[EcuIdentifier, TargetCustomImage]] = {
    Future.traverse(targets.toTraversable) { case (ecu: EcuIdentifier, (hw: HardwareIdentifier, CustomImage(image: Image, imgUri: Option[Uri], doDiff: Option[TargetFormat]))) =>
      doDiff match {
        case None => FastFuture.successful(ecu -> TargetCustomImage(image, hw, imgUri, None))
        case Some(targetFormat) =>
          val from = fromImage(currentImages(ecu), uri = None)
          val to = fromImage(image, imgUri)
          diffService.findDiffInfo(ns, targetFormat, from, to).flatMap {
            case None => FastFuture.failed(MtuDiffDataMissing)
            case Some(diffInfo) => FastFuture.successful(ecu -> TargetCustomImage(image, hw, imgUri, Some(diffInfo)))
          }
      }
    }.map(_.toMap)
  }

  private def tryToGenerate(namespace: Namespace, device: DeviceId, targetVersion: Int, timestampVersion: Int,
    correlationId: Option[CorrelationId]): Future[Done] = for {
    targets <- adminRepository.fetchCustomTargetVersion(namespace, device, targetVersion)
    currentImages <- adminRepository.findImages(namespace, device)
    customTargets <- generateCustomTargets(namespace, device, currentImages.toMap, targets)
    _ <- generateWithCustom(namespace, device, targetVersion, timestampVersion, customTargets,
                            correlationId.map(c => TargetsCustom(Some(c))))
  } yield Done

  def processFileCacheRequest(fcr: FileCacheRequest): Future[Unit] = for {
    _ <- tryToGenerate(fcr.namespace, fcr.device, fcr.targetVersion, fcr.timestampVersion, fcr.correlationId)
  } yield ()
}
