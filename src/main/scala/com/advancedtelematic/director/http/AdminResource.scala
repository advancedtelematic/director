package com.advancedtelematic.director.http

import org.slf4j.LoggerFactory

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatcher1
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.director.data.AdminRequest.{FindAffectedRequest, FindImageCount, RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.AkkaHttpUnmarshallingSupport._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.Messages.UpdateSpec
import com.advancedtelematic.director.data.MessageDataType.UpdateStatus
import com.advancedtelematic.director.db.{AdminRepositorySupport, AutoUpdateRepositorySupport, CancelUpdate, DeviceRepositorySupport, FileCacheRequestRepositorySupport, RepoNameRepositorySupport, SetMultiTargets, SetTargets}
import com.advancedtelematic.director.http.RepoResource.CreateRepositoryRequest
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.http.UUIDKeyPath._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId, ValidEcuSerial}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{KeyType, RsaKeyType, TargetName}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder}
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

object RepoResource {

  object CreateRepositoryRequest {
    implicit val encoder: Encoder[CreateRepositoryRequest] = io.circe.generic.semiauto.deriveEncoder
    implicit val decoder: Decoder[CreateRepositoryRequest] = io.circe.generic.semiauto.deriveDecoder
  }

  case class CreateRepositoryRequest(keyType: KeyType)
}

class AdminResource(extractNamespace: Directive1[Namespace], keyserverClient: KeyserverClient)
                   (implicit db: Database, ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport
    with AutoUpdateRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRequestRepositorySupport
    with RepoNameRepositorySupport {

  import RepoResource.CreateRepositoryRequest._

  private lazy val log = LoggerFactory.getLogger(this.getClass)

  val directorRepo = new DirectorRepo(keyserverClient)
  val setMultiTargets = new SetMultiTargets()
  val cancelUpdate = new CancelUpdate

  val EcuSerialPath = Segment.flatMap(_.refineTry[ValidEcuSerial].toOption)
  val TargetNamePath: PathMatcher1[TargetName] = Segment.map(TargetName.apply)

  val paginationParameters = (parameters('limit.as[Long].?) & parameters('offset.as[Long].?)).tmap { case (mLimit, mOffset) =>
    val limit  = mLimit.getOrElse(50L).min(1000)
    val offset = mOffset.getOrElse(0L)
    (limit, offset)
  }

  def createRepo(namespace: Namespace, keyType: KeyType) =
    complete {
      directorRepo.findOrCreate(namespace, keyType).map(StatusCodes.Created -> _)
    }

  def createRepo(namespace: Namespace): Route =
    entity(as[CreateRepositoryRequest]) { request =>
      log.debug(s"creating repo with key type ${request.keyType} for namespace $namespace")
      createRepo(namespace, request.keyType)
    } ~ createRepo(namespace, RsaKeyType)

  def registerDevice(namespace: Namespace, regDev: RegisterDevice): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete( Errors.PrimaryIsNotListedForDevice )
      case Some(_) =>
        val f = adminRepository.createDevice(namespace, regDev.vin, primEcu, regDev.ecus).map(StatusCodes.Created -> _)
        complete(f)
    }
  }

  def listInstalledImages(namespace: Namespace, device: DeviceId): Route = {
    val f = adminRepository.findImages(namespace, device)
    complete(f)
  }

  def getDevice(namespace: Namespace, device: DeviceId): Route = {
    val f = adminRepository.findDevice(namespace, device)
    complete(f)
  }

  def setTargets(namespace: Namespace, device: DeviceId, targets: SetTarget): Route = {
    val act = deviceRepository.findEcuSerials(namespace, device).flatMap { ecus =>
      if (!targets.updates.keys.toSet.subsetOf(ecus)) {
        FastFuture.failed(Errors.TargetsNotSubSetOfDevice)
      } else {
        SetTargets.setTargets(namespace, Seq(device -> targets))
      }
    }
    complete(act)
  }

  def setMultiUpdateTarget(namespace: Namespace, device: DeviceId, updateId: UpdateId): Route = {
    val f = setMultiTargets.setMultiUpdateTargets(namespace, device, updateId)
    complete(f)
  }

  def setMultiTargetUpdateForDevices(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId): Route = {
    val f = setMultiTargets.setMultiUpdateTargetsForDevices(namespace, devices, updateId)
    complete(f)
  }

  def fetchRoot(namespace: Namespace): Route = {
    val f = repoNameRepository.getRepo(namespace).flatMap { repo =>
      keyserverClient.fetchRootRole(repo)
    }
    complete(f)
  }

  def countInstalledImages(namespace: Namespace): Route =
    entity(as[FindImageCount]) { findReq =>
      complete(adminRepository.countInstalledImages(namespace, findReq.filepaths))
    }

  def findAffectedDevices(namespace: Namespace): Route = (paginationParameters & entity(as[FindAffectedRequest])) { (limit, offset, image) =>
    val f = adminRepository.findAffected(namespace, image.filepath, offset = offset, limit = limit)
    complete(f)
  }

  def findDevices(namespace: Namespace): Route = paginationParameters { (limit, offset) =>
    val f = adminRepository.findDevices(namespace, offset = offset, limit = limit)
    complete(f)
  }

  def findHardwareIdentifiers(namespace: Namespace): Route = paginationParameters { (limit, offset) =>
    val f = adminRepository.findAllHardwareIdentifiers(namespace, offset = offset, limit = limit)
    complete(f)
  }

  def findMultiTargetUpdateAffectedDevices(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId): Route = {
    val f = setMultiTargets.findAffected(namespace, devices, updateId)
    complete(f)
  }

  def getPublicKey(namespace: Namespace, device: DeviceId, ecuSerial: EcuSerial): Route = {
    val f = adminRepository.findPublicKey(namespace, device, ecuSerial)
    complete(f)
  }

  def queueForDevice(namespace: Namespace, device: DeviceId): Route = {
    val f = adminRepository.findQueue(namespace, device)
    complete(f)
  }

  def autoUpdateRoute(ns: Namespace, device: DeviceId, ecuSerial: EcuSerial): Route =
    pathPrefix("auto_update") {
      pathEnd {
        get {
          complete { autoUpdateRepository.findOnDevice(ns, device, ecuSerial) }
        } ~
        delete {
          complete { autoUpdateRepository.removeAll(ns, device, ecuSerial) }
        }
      } ~
      path(TargetNamePath) { targetName =>
        put {
          complete { autoUpdateRepository.persist(ns, device, ecuSerial, targetName) }
        } ~
        delete {
          complete { autoUpdateRepository.remove(ns, device, ecuSerial, targetName) }
        }
      }
    }

  def repoRoute(ns: Namespace): Route =
    pathPrefix("repo") {
      (pathEnd & post) {
        createRepo(ns)
      } ~
      (path("root.json") & get) {
        fetchRoot(ns)
      }
    }

  def ecusPath(ns: Namespace, device: DeviceId): Route =
    pathPrefix("ecus") {
      pathPrefix(EcuSerialPath) { ecuSerial =>
        autoUpdateRoute(ns, device, ecuSerial) ~
        (path("public_key") & get) {
          getPublicKey(ns, device, ecuSerial)
        }
      } ~
      (path("public_key") & parameters('ecu_serial.as[EcuSerial])) { ecuSerial =>
        getPublicKey(ns, device, ecuSerial)
      }
    }

  def devicePath(ns: Namespace): Route =
    pathPrefix(DeviceId.Path) { device =>
      ecusPath(ns, device) ~
      (pathEnd & get) {
        getDevice(ns, device)
      } ~
      (path("images") & get) {
        listInstalledImages(ns, device)
      } ~
      pathPrefix("queue") {
        get {
          queueForDevice(ns, device)
        } ~
        (path("cancel") & put) {
          val f = cancelUpdate.one(ns, device).flatMap{ res =>
            messageBusPublisher.publish(UpdateSpec(ns, device, UpdateStatus.Canceled)).map(_ => res)
          }
          complete(f)
        }
      } ~
      path("targets") {
        (put & entity(as[SetTarget])) { targets =>
          setTargets(ns, device, targets)
        }
      } ~
      path("multi_target_update" / UpdateId.Path) { updateId =>
        put {
          setMultiUpdateTarget(ns, device, updateId)
        }
      }
    }

  def multiTargetUpdatesRoute(ns: Namespace): Route =
    pathPrefix("multi_target_updates" / UpdateId.Path) { updateId =>
      (pathEnd & put & entity(as[Seq[DeviceId]])) { devices =>
        setMultiTargetUpdateForDevices(ns, devices, updateId)
      } ~
        (path("affected") & get & entity(as[Seq[DeviceId]])) { devices =>
        findMultiTargetUpdateAffectedDevices(ns, devices, updateId)
      }
    }

  val route: Route = extractNamespace { ns =>
    pathPrefix("admin") {
      // this is deprecated, should use repo/root.json
      (get & path("root.json")) {
         fetchRoot(ns)
      } ~
      repoRoute(ns) ~
      pathPrefix("images") {
        (get & path("affected")) {
          findAffectedDevices(ns)
        } ~
        (post & path("installed_count")) { // this is post because front-end can't send
          countInstalledImages(ns)         // request body with get
        }
      } ~
      multiTargetUpdatesRoute(ns) ~
      pathPrefix("devices") {
        pathEnd {
          (post & entity(as[RegisterDevice]))  { regDev =>
            registerDevice(ns, regDev)
          } ~
          get {
            findDevices(ns)
          }
        } ~
        (path("queue" / "cancel") & put & entity(as[Seq[DeviceId]])) { devices =>
          val f = cancelUpdate.several(ns, devices).flatMap { canceledDevices =>
            Future.traverse(canceledDevices) { dev => messageBusPublisher.publish(UpdateSpec(ns, dev, UpdateStatus.Canceled))}.map(_ => canceledDevices)
          }
          complete(f)
        } ~
        (get & path("hardware_identifiers")) {
          findHardwareIdentifiers(ns)
        } ~
        devicePath(ns)
      }
    }
  }
}
