package com.advancedtelematic.director.daemon

import akka.Done
import com.advancedtelematic.director.data.DataType.Namespace
import com.advancedtelematic.director.http.RegisterNamespace
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import org.genivi.sota.messaging.Messages.UserCreated
import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._
import scala.async.Async._

object UserCreatedListener {
  def action(tufClient: KeyserverClient)(event: UserCreated)(implicit db: Database, ec: ExecutionContext): Future[Done] = async {
    val namespace = Namespace(event.id)

    await(RegisterNamespace.action(tufClient, namespace))

    Done
  }
}
