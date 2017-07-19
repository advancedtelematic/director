package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatcher1
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.director.data.AdminRequest.{FindAffectedRequest, RegisterDevice, SetTarget}
import com.advancedtelematic.director.data.AkkaHttpUnmarshallingSupport._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.db.{AdminRepositorySupport, AutoUpdateRepositorySupport, DeviceRepositorySupport, FileCacheRequestRepositorySupport, RepoNameRepositorySupport,
  SetMultiTargets, SetTargets}
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.libats.codecs.AkkaCirce._
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, UpdateId, ValidEcuSerial}
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.TargetName
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import scala.concurrent.ExecutionContext
import scala.async.Async._
import slick.jdbc.MySQLProfile.api._

class AdminResource(extractNamespace: Directive1[Namespace],
                    keyserverClient: KeyserverClient)
                   (implicit db: Database, ec: ExecutionContext, mat: Materializer, messageBusPublisher: MessageBusPublisher)
    extends AdminRepositorySupport
    with AutoUpdateRepositorySupport
    with DeviceRepositorySupport
    with FileCacheRequestRepositorySupport
    with RepoNameRepositorySupport {

  val directorRepo = new DirectorRepo(keyserverClient)
  val setMultiTargets = new SetMultiTargets()

  val EcuSerialPath = Segment.flatMap(_.refineTry[ValidEcuSerial].toOption)
  val TargetNamePath: PathMatcher1[TargetName] = Segment.map(TargetName.apply)

  def createRepo(namespace: Namespace): Route = complete {
    directorRepo.findOrCreate(namespace).map(StatusCodes.Created -> _)
  }

  def registerDevice(namespace: Namespace, regDev: RegisterDevice): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete( Errors.PrimaryIsNotListedForDevice )
      case Some(_) => complete( StatusCodes.Created ->
                                 adminRepository.createDevice(namespace, regDev.vin, primEcu, regDev.ecus))
    }
  }

  def listInstalledImages(namespace: Namespace, device: DeviceId): Route = {
    complete(adminRepository.findImages(namespace, device))
  }

  def getDevice(namespace: Namespace, device: DeviceId): Route = {
    complete(adminRepository.findDevice(namespace, device))
  }

  def setTargets(namespace: Namespace, device: DeviceId, targets: SetTarget): Route = {
    val act = async {
      val ecus = await(deviceRepository.findEcus(namespace, device)).map(_.ecuSerial).toSet

      if (!targets.updates.keys.toSet.subsetOf(ecus)) {
        await(FastFuture.failed(Errors.TargetsNotSubSetOfDevice))
      } else {
        await(SetTargets.setTargets(namespace, Seq(device -> targets)))
      }
    }
    complete(act)
  }

  def setMultiUpdateTarget(namespace: Namespace, device: DeviceId, updateId: UpdateId): Route = {
    complete {
      setMultiTargets.setMultiUpdateTargets(namespace, device, updateId)
    }
  }

  def setMultiTargetUpdateForDevices(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId): Route = complete {
    setMultiTargets.setMultiUpdateTargetsForDevices(namespace, devices, updateId)
  }

  def fetchRoot(namespace: Namespace): Route = {
    val f = repoNameRepository.getRepo(namespace).flatMap { repo =>
      keyserverClient.fetchRootRole(repo)
    }
    complete(f)
  }

  def findAffectedDevices(namespace: Namespace): Route =
    (parameters('limit.as[Long].?) & parameters('offset.as[Long].?) & entity(as[FindAffectedRequest])) { (mLimit, mOffset, image) =>
      val offset = mOffset.getOrElse(0L)
      val limit  = mLimit.getOrElse(50L)
      complete(adminRepository.findAffected(namespace, image.filepath, offset = offset, limit = limit))
    }

  def findHardwareIdentifiers(namespace: Namespace): Route =
    (parameters('limit.as[Long].?) & parameters('offset.as[Long].?)) { (mLimit, mOffset) =>
      val offset = mOffset.getOrElse(0L)
      val limit  = mLimit.getOrElse(50L).min(1000)
      complete(adminRepository.findAllHardwareIdentifiers(namespace, offset = offset, limit = limit))
    }


  def findMultiTargetUpdateAffectedDevices(namespace: Namespace, devices: Seq[DeviceId], updateId: UpdateId): Route = complete {
    setMultiTargets.findAffected(namespace, devices, updateId)
  }

  def getPublicKey(namespace: Namespace, device: DeviceId, ecuSerial: EcuSerial): Route = complete {
    adminRepository.findPublicKey(namespace, device, ecuSerial)
  }

  def queueForDevice(namespace: Namespace, device: DeviceId): Route = complete {
    adminRepository.findQueue(namespace, device)
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
      (path("queue") & get) {
        queueForDevice(ns, device)
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
        }
      } ~
      multiTargetUpdatesRoute(ns) ~
      pathPrefix("devices") {
        (pathEnd & post & entity(as[RegisterDevice]))  { regDev =>
          registerDevice(ns, regDev)
        } ~
        (get & path("hardware_identifiers")) {
          findHardwareIdentifiers(ns)
        } ~
        devicePath(ns)
      }
    }
  }
}
