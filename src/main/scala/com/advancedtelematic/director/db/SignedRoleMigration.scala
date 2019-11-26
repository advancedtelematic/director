package com.advancedtelematic.director.db

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.advancedtelematic.libats.codecs.CirceCodecs.checkSumEncoder
import com.advancedtelematic.libats.data.DataType.Checksum
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf_server.crypto.Sha256Digest
import io.circe.syntax._
import slick.jdbc.GetResult._
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.{GetResult, PositionedParameters, SetParameter}

import scala.concurrent.{ExecutionContext, Future}

// migrate director.file_cache to director2.signed_roles
// (director2.checksum can only be created in Scala.)
class SignedRoleMigration(old_director_schema: String = "director")
                         (implicit val db: Database, val mat: Materializer, val ec: ExecutionContext) {

  implicit val getRoleTypeResult = GetResult(r => RoleType.withName(r.nextString()))
  implicit val setRoleTypeParameter: SetParameter[RoleType] =
    (roleType: RoleType, pp: PositionedParameters) => pp.setString(roleType.toString)

  implicit val getDeviceIdResult = GetResult(r => DeviceId(UUID.fromString(r.nextString)))
  implicit val setDeviceIdParameter: SetParameter[DeviceId] =
    (deviceId: DeviceId, pp: PositionedParameters) => pp.setString(deviceId.uuid.toString)

  implicit val getInstantResult = GetResult(r => r.nextTimestamp().toInstant)
  implicit val setInstantParameter: SetParameter[Instant] =
    (instant: Instant, pp: PositionedParameters) => pp.setTimestamp(Timestamp.from(instant))

  implicit val setChecksumParameter: SetParameter[Checksum] =
    (checksum: Checksum, pp: PositionedParameters) => pp.setString(checksum.asJson.noSpaces)

  def exists(schema: String, table: String): Future[Boolean] = {
    val sql = sql"SELECT EXISTS(SELECT * FROM information_schema.tables WHERE table_schema = $schema AND table_name = $table)"
                      .as[Boolean]
    db.run(sql).map(_.head)
  }

  def fileCache: Source[(RoleType, Int, DeviceId, String, Instant, Instant, Instant), NotUsed] = {
    val sql = sql""" select role,version,device,file_entity,created_at,updated_at,expires from #$old_director_schema.file_cache """
                                              .as[(RoleType,Int,DeviceId,String,Instant,Instant,Instant)]
    Source.fromPublisher(db.stream(sql))
  }

  def writeSignedRole(role: RoleType, version: Int, deviceId: DeviceId, content: String, createdAt: Instant, updatedAt: Instant, expiresAt: Instant): Future[String] = {
    val canonicalJson = content.asJson.canonical
    val checksum = Sha256Digest.digest(canonicalJson.getBytes)
    val length = canonicalJson.length
    val sql =
      sqlu"""insert into signed_roles value ($role,$version,$deviceId,$checksum,$length,$content,$createdAt,$updatedAt,$expiresAt)
        on duplicate key update checksum=$checksum,length=$length,content=$content,created_at=$createdAt,updated_at=$updatedAt,expires_at=$expiresAt
          """
    db.run(sql).map(_ => content)
  }

  def run: Future[Done] = {
    exists(old_director_schema, "file_cache").flatMap { flag =>
      if (flag)
        fileCache.mapAsync(1)((writeSignedRole _).tupled).runWith(Sink.ignore)
      else
        FastFuture.successful(Done)
    }
  }

}
