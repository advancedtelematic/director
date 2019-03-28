package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import com.advancedtelematic.director.data.AdminDataType.MultiTargetUpdate
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
import slick.jdbc.MySQLProfile.api.Database
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.db.MultiTargetUpdates
import com.advancedtelematic.libats.data.DataType.Namespace

import scala.concurrent.{ExecutionContext, Future}



class MultiTargetUpdatesResource(extractNamespace: Directive1[Namespace])(implicit val db: Database, val ec: ExecutionContext) {
  import Directives._

  val multiTargetUpdates = new MultiTargetUpdates()

  val route = extractNamespace { ns =>
    pathPrefix("multi_target_updates") {
      (get & pathPrefix(UpdateId.Path)) { uid =>
        complete(multiTargetUpdates.find(ns, uid))
      } ~
      (post & pathEnd) {
        entity(as[MultiTargetUpdate]) { mtuRequest =>
          val f = multiTargetUpdates.create(ns, mtuRequest).map {
            StatusCodes.Created -> _
          }

          complete(f)
        }
      }
    }
  }
}
