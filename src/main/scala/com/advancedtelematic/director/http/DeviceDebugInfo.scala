package com.advancedtelematic.director.http

import akka.http.scaladsl.server.{Directives, _}
import com.advancedtelematic.director.data.AdminRequest.EcuInfoResponse
import com.advancedtelematic.director.db.{AdminRepositorySupport, FileCacheRepositorySupport}
import com.advancedtelematic.director.http.DeviceDebugInfo.DeviceDebugResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

object DeviceDebugInfo {
  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe._
  import io.circe.generic.semiauto._
  import com.advancedtelematic.director.data.Codecs._

  case class DeviceDebugResult(deviceId: DeviceId, targets: Seq[Json], ecus: Seq[EcuInfoResponse])

  implicit val deviceDebugResultEncoder: Encoder[DeviceDebugResult] = deriveEncoder
  implicit val deviceDebugResultDecoder: Decoder[DeviceDebugResult] = deriveDecoder
}

class DeviceDebugInfo extends FileCacheRepositorySupport with AdminRepositorySupport {
    def find(deviceId: DeviceId)(implicit db: Database, ec: ExecutionContext): Future[DeviceDebugResult] = for {
    ecus <- adminRepository.findDeviceById(deviceId)
    targets <- fileCacheRepository.fetchDeviceTargets(deviceId)
  } yield DeviceDebugResult(deviceId, targets, ecus)
}


class DeviceDebugInfoResource()(implicit db: Database, ec: ExecutionContext) {
  import DeviceDebugInfo._
  import Directives._
  import com.advancedtelematic.libats.http.UUIDKeyAkka._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  val deviceDebug = new DeviceDebugInfo

  def route: Route = {
    pathPrefix("admin") {
      (get & path("device" / DeviceId.Path)) { device =>
        complete(deviceDebug.find(device))
      }
    }
  }
}