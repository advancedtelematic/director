package com.advancedtelematic.director.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import cats.syntax.show.toShowOps
import com.advancedtelematic.director.data.DataType.{DeviceId, Namespace, UpdateId}
import com.advancedtelematic.libats.http.ErrorCode
import com.advancedtelematic.libats.http.Errors.RawError

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

trait CoreClient {
  protected def CoreError(msg: String) = RawError(ErrorCode("core_remote_error"), StatusCodes.BadGateway, msg)

  def updateReportOk(namespace: Namespace, device: DeviceId, update: UpdateId): Future[Unit]
  def updateReportFail(namespace: Namespace, device: DeviceId, update: UpdateId, reason: String): Future[Unit]
}

object CoreHttpClient {
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto._

  final case class OperationResult(id: UpdateId, result_code: Int, result_text: String)

  implicit lazy val decodeOperationResult: Decoder[OperationResult] = deriveDecoder
  implicit lazy val encodeOperationResult: Encoder[OperationResult] = deriveEncoder
}

class CoreHttpClient(uri: Uri)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) extends CoreClient {
  import de.heikoseeberger.akkahttpcirce.CirceSupport._
  import io.circe.syntax._
  import CoreHttpClient._

  private val _http = Http()

  override def updateReportOk(namespace: Namespace, device: DeviceId, update: UpdateId): Future[Unit] = {
    val operationResult = OperationResult(update, 0, "device reported correct images to director")
    val entity = HttpEntity(ContentTypes.`application/json`, operationResult.asJson.noSpaces)
    val req = HttpRequest(HttpMethods.POST,
                          uri= uri.withPath(uri.path / "mydevice" / device.show / "updates" / update.show),
                          entity = entity)
    execHttp[Unit](namespace, req)
  }

  override def updateReportFail(namespace: Namespace, device: DeviceId, update: UpdateId, reason: String): Future[Unit] = {
    val operationResult = OperationResult(update, 4, reason)
    val entity = HttpEntity(ContentTypes.`application/json`, operationResult.asJson.noSpaces)
    val req = HttpRequest(HttpMethods.POST,
                          uri= uri.withPath(uri.path / "mydevice" / device.show / "updates" / update.show),
                          entity = entity)
    execHttp[Unit](namespace, req)
  }

  private def execHttp[T : ClassTag](namespace: Namespace, request: HttpRequest)
                      (implicit um: FromEntityUnmarshaller[T]): Future[T] = {
    _http.singleRequest(request.withHeaders(RawHeader("x-ats-namespace", namespace.get))).flatMap {
      case r @ HttpResponse(status, _, _,_) if status.isSuccess() =>
        um(r.entity)
      case r =>
        FastFuture.failed(CoreError(s"Unexpected response from Core: $r"))
    }
  }
}
