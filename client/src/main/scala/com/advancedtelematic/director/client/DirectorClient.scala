package com.advancedtelematic.director.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import cats.syntax.show._
import com.advancedtelematic.director.client.DataType.SetMultiTargetUpdate
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.ServiceHttpClient
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import io.circe.Encoder
import Codecs._
import scala.concurrent.Future
import scala.reflect.ClassTag
import com.advancedtelematic.libats.codecs.CirceCodecs._
import DeviceId._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import cats.syntax.option._

trait DirectorClient {
  def setMultiUpdateTarget[M : Encoder](
    ns: Namespace,
    update: UpdateId,
    devices: Seq[DeviceId],
    metadata: Option[M]): Future[Seq[DeviceId]]

  def cancelUpdate(
    ns: Namespace,
    devices: Seq[DeviceId]): Future[Seq[DeviceId]]

  def cancelUpdate(
    ns: Namespace,
    device: DeviceId): Future[Unit]
}

// TODO:SM write tests for this
class DirectorHttpClient(uri: Uri, httpClient: HttpRequest => Future[HttpResponse])(implicit system: ActorSystem, mat: Materializer)
  extends ServiceHttpClient(httpClient) with DirectorClient {

  import io.circe.syntax._

  override def setMultiUpdateTarget[M : Encoder](ns: Namespace, update: UpdateId, devices: Seq[DeviceId], metadata: Option[M]): Future[Seq[DeviceId]] = {
    val path   = uri.path / "api" / "v1" / "admin" / "multi_target_updates" / update.show
    val payload = SetMultiTargetUpdate(devices)
    val entity = HttpEntity(ContentTypes.`application/json`, payload.asJson.noSpaces)
    val req    = HttpRequest(
      method = HttpMethods.PUT,
      uri    = uri.withPath(path),
      entity = entity
    )
    execForNamespace[Seq[DeviceId]](ns, req)
  }

  override def cancelUpdate(ns: Namespace, devices: Seq[DeviceId]): Future[Seq[DeviceId]] = {
    val path   = uri.path / "api" / "v1" / "admin" / "devices" / "queue" / "cancel"
    val entity = HttpEntity(ContentTypes.`application/json`, devices.asJson.noSpaces)
    val req    = HttpRequest(
      method = HttpMethods.PUT,
      uri    = uri.withPath(path),
      entity = entity
    )
    execForNamespace[Seq[DeviceId]](ns, req)
  }

  override def cancelUpdate(ns: Namespace, device: DeviceId): Future[Unit] = {
    val path = uri.path / "api" / "v1" / "admin" / "devices" / device.show / "queue" / "cancel"
    val req = HttpRequest(method = HttpMethods.PUT, uri = uri.withPath(path))
    execForNamespace[Unit](ns, req)
  }

  protected def execForNamespace[T: ClassTag](namespace: Namespace, request: HttpRequest)(implicit um: FromEntityUnmarshaller[T]): Future[T] = {
    execHttp(request.addHeader(RawHeader("x-ats-namespace", namespace.get)))()
  }
}
