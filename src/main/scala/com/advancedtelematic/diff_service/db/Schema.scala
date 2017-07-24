package com.advancedtelematic.diff_service.db

import akka.http.scaladsl.model.Uri
import com.advancedtelematic.diff_service.data.DataType.{BsDiff, StaticDelta, BsDiffInfo, StaticDeltaInfo}
import com.advancedtelematic.diff_service.data.DataType.DiffStatus.DiffStatus
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{BsDiffRequestId, Checksum, Commit, DeltaRequestId,
  HashMethod, ValidChecksum}
import com.advancedtelematic.libats.messaging_datatype.DataType.HashMethod.HashMethod
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickUriMapper._
import eu.timepit.refined.api.Refined
import slick.jdbc.MySQLProfile.api._

object Schema {
  // `HashMethod` should extend `SlickEnum`, but until then we have this implicit
  implicit val hashMethodColumn = MappedColumnType.base[HashMethod, String](_.toString, HashMethod.withName)

  class BsDiffs(tag: Tag) extends Table[BsDiff](tag, "diff_bs_diffs") {
    def namespace = column[Namespace]("namespace")
    def id = column[BsDiffRequestId]("id")
    def from = column[Uri]("from")
    def to = column[Uri]("to")
    def status = column[DiffStatus]("status")

    def pk = primaryKey("bs_diff_pk", id)

    override def * = (namespace, id, from, to, status) <> ((BsDiff.apply _).tupled, BsDiff.unapply)
  }
  protected [db] val bsDiffs = TableQuery[BsDiffs]

  class BsDiffInfos(tag: Tag) extends Table[BsDiffInfo](tag, "diff_bs_diff_infos") {
    def id = column[BsDiffRequestId]("id")
    def checksumMethod = column[HashMethod]("hash_method")
    def checksumHash   = column[Refined[String, ValidChecksum]]("hash")
    def size = column[Long]("size")
    def uri  = column[Uri]("uri")

    def checksum = (checksumMethod, checksumHash) <> ((Checksum.apply _).tupled, Checksum.unapply)
    override def * = (id, checksum, size, uri) <> ((BsDiffInfo.apply _).tupled, BsDiffInfo.unapply)

    def pk = primaryKey("bs_diff_info_pk", id)
  }
  protected [db] val bsDiffInfos = TableQuery[BsDiffInfos]

  class StaticDeltas(tag: Tag) extends Table[StaticDelta](tag, "diff_static_deltas") {
    def namespace = column[Namespace]("namespace")
    def id = column[DeltaRequestId]("id")
    def from = column[Commit]("from")
    def to = column[Commit]("to")
    def status = column[DiffStatus]("status")

    override def * = (namespace, id, from, to, status) <> ((StaticDelta.apply _).tupled, StaticDelta.unapply)

    def pk = primaryKey("static_delta_pk", id)
  }
  protected [db] val staticDeltas = TableQuery[StaticDeltas]

  class StaticDeltaInfos(tag: Tag) extends Table[StaticDeltaInfo](tag, "diff_static_delta_infos") {
    def id = column[DeltaRequestId]("id")
    def checksumMethod = column[HashMethod]("hash_method")
    def checksumHash   = column[Refined[String, ValidChecksum]]("hash")
    def size = column[Long]("size")
    def uri  = column[Uri]("uri")

    def checksum = (checksumMethod, checksumHash) <> ((Checksum.apply _).tupled, Checksum.unapply)
    def * = (id, checksum, size, uri) <> ((StaticDeltaInfo.apply _).tupled, StaticDeltaInfo.unapply)

    def pk = primaryKey("static_delta_info_pk", id)
  }
  protected [db] val staticDeltaInfos = TableQuery[StaticDeltaInfos]

}
