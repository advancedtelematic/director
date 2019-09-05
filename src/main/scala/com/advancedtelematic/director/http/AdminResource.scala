package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.syntax.option._
import com.advancedtelematic.director.data.AdminDataType.{FindImageCount, RegisterDevice}
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.db.{AutoUpdateDefinitionRepositorySupport, DeviceRegistration, DeviceRepositorySupport, EcuRepositorySupport, RepoNamespaceRepositorySupport}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, RepoId, TargetName}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class RepositoryCreation(keyserverClient: KeyserverClient)(implicit val db: Database, val ec: ExecutionContext)
  extends DeviceRepositorySupport with RepoNamespaceRepositorySupport {

  def create(ns: Namespace): Future[Unit] = {
    val repoId = RepoId.generate()

    for {
      _ <- keyserverClient.createRoot(repoId, Ed25519KeyType, forceSync = true)
      _ <- repoNamespaceRepo.persist(repoId, ns)
    } yield ()
  }
}

class AdminResource(extractNamespace: Directive1[Namespace], val keyserverClient: KeyserverClient)
                   (implicit val db: Database, val ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
  extends NamespaceRepoId
    with RepoNamespaceRepositorySupport
    with RootFetching
    with EcuRepositorySupport
    with AutoUpdateDefinitionRepositorySupport {

  private val EcuIdPath = Segment.flatMap(EcuIdentifier(_).toOption)
  private val TargetNamePath: PathMatcher1[TargetName] = Segment.map(TargetName.apply)

  val deviceRegistration = new DeviceRegistration(keyserverClient)
  val repositoryCreation = new RepositoryCreation(keyserverClient)

  val paginationParameters: Directive[(Long, Long)] =
    (parameters('limit.as[Long].?) & parameters('offset.as[Long].?)).tmap { case (mLimit, mOffset) =>
      val limit = mLimit.getOrElse(50L).min(1000)
      val offset = mOffset.getOrElse(0L)
      (limit, offset)
    }

  def repoRoute(ns: Namespace): Route =
    pathPrefix("repo") {
      (post & pathEnd) {
        val f = repositoryCreation.create(ns).map(_ => StatusCodes.Created)
        complete(f)
      } ~
        get {
          path("root.json") {
            complete(fetchRoot(ns, version = None))
          } ~
            path(IntNumber ~ ".root.json") { version ⇒
              complete(fetchRoot(ns, version.some))
            }
        }
    }

  def devicePath(ns: Namespace): Route =
    pathPrefix(DeviceId.Path) { device =>
      pathPrefix("ecus") {
        pathPrefix(EcuIdPath) { ecuId =>
          pathPrefix("auto_update") {
            (pathEnd & get) {
              complete(autoUpdateDefinitionRepository.findOnDevice(ns, device, ecuId).map(_.map(_.targetName)))
            } ~
              path(TargetNamePath) { targetName =>
                put {
                  complete(autoUpdateDefinitionRepository.persist(ns, device, ecuId, targetName).map(_ => StatusCodes.NoContent))
                } ~
                delete {
                  complete(autoUpdateDefinitionRepository.remove(ns, device, ecuId, targetName).map(_ => StatusCodes.NoContent))
                }
              }
          } ~
            (path("public_key") & get) {
              val key = ecuRepository.findBySerial(ns, device, ecuId).map(_.publicKey)
              complete(key)
            }
        }
      } ~
        (pathEnd & get) {
          val f = deviceRegistration.findDeviceEcuInfo(ns, device)
          complete(f)
        }
    }

  val route: Route = extractNamespace { ns =>
    pathPrefix("admin") {
      repoRoute(ns) ~
        pathPrefix("images") {
          (post & path("installed_count")) { // this is post because front-end can't send
            entity(as[FindImageCount]) { findImageReq =>
              val f = ecuRepository.countEcusWithImages(findImageReq.filepaths.toSet)
              complete(f)
            }
          }
        } ~
        pathPrefix("devices") {
          UserRepoId(ns) { repoId =>
            pathEnd {
              (post & entity(as[RegisterDevice])) { regDev =>
                if (regDev.deviceId.isEmpty)
                  reject(ValidationRejection("deviceId is required to register a device"))
                else {
                  val f = deviceRegistration.register(ns, repoId, regDev.deviceId.get, regDev.primary_ecu_serial, regDev.ecus)
                  complete(f.map(_ => StatusCodes.Created))
                }
              }
            } ~
              (get & path("hardware_identifiers")) {
                paginationParameters { (limit, offset) =>
                  val f = ecuRepository.findAllHardwareIdentifiers(ns, offset, limit)
                  complete(f)
                }
              } ~
              devicePath(ns)
          }
        }
    }
  }
}
