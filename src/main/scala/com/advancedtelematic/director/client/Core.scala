package com.advancedtelematic.director.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import cats.syntax.show.toShowOps
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.director.data.DeviceRequest.OperationResult
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.ErrorCode
import com.advancedtelematic.libats.http.Errors.RawError

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

trait CoreClient {
  protected def CoreError(msg: String) = RawError(ErrorCode("core_remote_error"), StatusCodes.BadGateway, msg)

  def updateReport(namespace: Namespace, device: DeviceId, update: UpdateId, operationResults: Seq[OperationResult]): Future[Unit]
}

object CoreHttpClient {
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto._

  final case class CoreOperationResult(id: UpdateId, result_code: Int, result_text: String)

  implicit lazy val decodeOperationResult: Decoder[CoreOperationResult] = deriveDecoder
  implicit lazy val encodeOperationResult: Encoder[CoreOperationResult] = deriveEncoder

  final case class NoContent()

  implicit val noContentUnmarshaller: FromEntityUnmarshaller[NoContent]
    = Unmarshaller { implicit ec => response =>
      FastFuture.successful(NoContent())
    }
}

class CoreHttpClient(uri: Uri)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) extends CoreClient {
  import io.circe.syntax._
  import CoreHttpClient._

  private val _http = Http()

  override def updateReport(namespace: Namespace, device: DeviceId, update: UpdateId, operations: Seq[OperationResult]): Future[Unit] = {
    val operationResults = operations.map{ op =>
      CoreOperationResult(update, op.result_code, op.result_text)
    }
    val entity = HttpEntity(ContentTypes.`application/json`, operationResults.asJson.noSpaces)
    val req = HttpRequest(HttpMethods.POST,
                          uri= uri.withPath(uri.path / "api" / "v1" / "mydevice" / device.show / "updates" / update.show),
                          entity = entity)
    execHttp[NoContent](namespace, req).map(_ => ())
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
