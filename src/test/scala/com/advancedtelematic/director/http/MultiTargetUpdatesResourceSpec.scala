package com.advancedtelematic.director.http

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.director.data.DataType.{FileInfo, Image, UpdateId}
import com.advancedtelematic.director.db.MultiTargetUpdatesRepositorySupport
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}

class MultiTargetUpdatesResourceSpec extends DirectorSpec with DefaultPatience with ResourceSpec with Requests
  with MultiTargetUpdatesRepositorySupport {

  import com.advancedtelematic.director.data.GeneratorOps._

  test("fetch target info") {
    val mtu1 = GenMultiTargetUpdateCreated.generate
    val mtu2 = GenMultiTargetUpdateCreated.generate
    val clientHash1 = Map(mtu1.checksum.method -> mtu1.checksum.hash)
    val clientHash2 = Map(mtu2.checksum.method -> mtu2.checksum.hash)
    val expectedResult =
      Map[String, Image](mtu1.hardwareId -> Image(mtu1.target, FileInfo(clientHash1, mtu1.targetLength)),
                         mtu2.hardwareId -> Image(mtu2.target, FileInfo(clientHash2, mtu2.targetLength)))
    val f = for {
      _ <- multiTargetUpdatesRepository.create(mtu1)
      _ <- multiTargetUpdatesRepository.create(mtu2.copy(id = mtu1.id))
    } yield ()

    f.map { _ =>
      fetchMultiTargetUpdate(mtu1.id) shouldBe expectedResult
    }
  }

  test("fetching non-existent target info returns 404") {
    val id = UpdateId.generate()
    Get(apiUri(s"multi_target_updates/${id.uuid.toString}")) ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }
}
