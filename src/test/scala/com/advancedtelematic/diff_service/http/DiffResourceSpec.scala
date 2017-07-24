package com.advancedtelematic.diff_service.http

import akka.http.scaladsl.model.{StatusCodes, Uri}
import com.advancedtelematic.diff_service.client.DiffServiceDirectorClient
import com.advancedtelematic.diff_service.data.Codecs._
import com.advancedtelematic.diff_service.data.DataType._
import com.advancedtelematic.diff_service.util.{DiffServiceSpec, ResourceSpec}
import com.advancedtelematic.director.util.DefaultPatience
import com.advancedtelematic.director.util.NamespaceTag.Namespaced
import com.advancedtelematic.libats.messaging_datatype.Messages.{GeneratedBsDiff, GeneratedDelta}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.{BINARY, OSTREE}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

class DiffResourceSpec extends DiffServiceSpec
    with DefaultPatience
    with ResourceSpec {

  testWithNamespace("can create static delta") { implicit ns =>

    val request = GenCreateDiffInfoRequest(OSTREE).sample.get
    Post(apiUri("diffs"), Seq(request)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val fromCommit = DiffServiceDirectorClient.convertToCommit(request.from)
    val toCommit = DiffServiceDirectorClient.convertToCommit(request.to)

    val query = StaticDeltaQuery(fromCommit, toCommit)
    val id = Get(apiUri("diffs/static_delta"), query).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val resp = responseAs[StaticDeltaQueryResponse]
      resp.status shouldBe DiffStatus.REQUESTED
      resp.diff shouldBe None
      resp.id
    }

    val resultUri = Uri("http://example.com/result")
    val size = 22
    val checksum = GenChecksum.sample.get

    val generatedDelta = GeneratedDelta(id, ns.get, fromCommit, toCommit, resultUri, size, checksum)
    diffListener.generatedDeltaAction(generatedDelta).futureValue

    Get(apiUri("diffs/static_delta"), query).namespaced ~> routes ~> check {
      val resp = responseAs[StaticDeltaQueryResponse]
      status shouldBe StatusCodes.OK
      resp.id shouldBe id
      resp.status shouldBe DiffStatus.GENERATED

      resp.diff shouldBe defined
      val info = resp.diff.get

      info.id shouldBe id
      info.checksum shouldBe checksum
      info.size shouldBe size
      info.resultUri shouldBe resultUri
    }
  }

  testWithNamespace("can create bs diff") { implicit ns =>

    val request = GenCreateDiffInfoRequest(BINARY).sample.get
    Post(apiUri("diffs"), Seq(request)).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val fromUri = DiffServiceDirectorClient.convertToBinaryUri(tufBinaryUri, request.from)
    val toUri = DiffServiceDirectorClient.convertToBinaryUri(tufBinaryUri, request.to)

    val query = BsDiffQuery(fromUri, toUri)
    val id = Get(apiUri("diffs/bs_diff"), query).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      val resp = responseAs[BsDiffQueryResponse]
      resp.status shouldBe DiffStatus.REQUESTED
      resp.diff shouldBe None
      resp.id
    }

    val resultUri = Uri("http://example.com/result")
    val size = 22
    val checksum = GenChecksum.sample.get

    val generatedBsDiff = GeneratedBsDiff(id, ns.get, fromUri, toUri, resultUri, size, checksum)
    diffListener.generatedBsDiffAction(generatedBsDiff).futureValue

    Get(apiUri("diffs/bs_diff"), query).namespaced ~> routes ~> check {
      val resp = responseAs[BsDiffQueryResponse]
      status shouldBe StatusCodes.OK
      resp.id shouldBe id
      resp.status shouldBe DiffStatus.GENERATED

      resp.diff shouldBe defined
      val info = resp.diff.get

      info.id shouldBe id
      info.checksum shouldBe checksum
      info.size shouldBe size
      info.resultUri shouldBe resultUri
    }
  }
}
