package com.advancedtelematic.director.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, ResponseEntity}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.Role.Role
import io.circe.Json
import org.genivi.sota.data.Uuid
import org.slf4j.LoggerFactory
import scala.reflect.ClassTag
import scala.concurrent.{Future, ExecutionContext}

class TufClient(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext) extends Tuf {

  private val http = Http()

  private val log = LoggerFactory.getLogger(this.getClass)

  // this returns keys..
  override def initRepo(uuid: Uuid): Future[Unit] = {
    ??? // execHttp[Unit](HttpRequest(uri = baseUri.withPath())
  }

  override def sign[T](uuid: Uuid, role: Role, value: Json): Future[Json] = {
    ???
  }

  private def execHttp[T](httpRequest: HttpRequest)
                      (implicit unmarshaller: Unmarshaller[ResponseEntity, T],
                       ct: ClassTag[T]): Future[T] = {
    http.singleRequest(httpRequest).flatMap { response =>
      response.status match {
        case other if other.isSuccess() => unmarshaller(response.entity)
        case err =>
          log.error(s"Got exception from ${httpRequest.uri}\n" +
                    s"Error message: ${response.entity.toString}")
          FastFuture.failed(new Exception(err.toString))
      }
    }
  }

}
