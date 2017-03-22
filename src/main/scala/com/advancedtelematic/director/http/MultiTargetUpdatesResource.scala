package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType.{FileInfo, Image, UpdateId}
import com.advancedtelematic.director.db.MultiTargetUpdatesRepositorySupport
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import slick.driver.MySQLDriver.api.Database

import scala.async.Async._
import scala.concurrent.ExecutionContext

class MultiTargetUpdatesResource()(implicit db: Database, ec: ExecutionContext)
  extends MultiTargetUpdatesRepositorySupport {

  import Directives._

  def getTargetInfo(id: UpdateId): Route = {
    val f = async {
      val rows = await(multiTargetUpdatesRepository.fetch(id))
      rows.foldLeft(Map[String, Image]()) { (map, mtu) =>
        val clientHash = Map(mtu.checksum.method -> mtu.checksum.hash)
        map + (mtu.hardwareId -> Image(mtu.target, FileInfo(clientHash, mtu.targetLength)))
      }
    }
    complete(f)
  }

  val route =
    (pathPrefix("multi_target_updates" / UpdateId.Path) & get) { updateRequestId =>
      getTargetInfo(updateRequestId)
    }
}
