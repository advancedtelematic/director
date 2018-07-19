package com.advancedtelematic.director.migration

import java.util.concurrent.ConcurrentHashMap

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import cats.syntax.show._
import com.advancedtelematic.director.Settings
import com.advancedtelematic.director.db.{AdminRepositorySupport, AutoUpdateRepositorySupport, Errors => DBErrors, RepoNameRepositorySupport}
import com.advancedtelematic.libats.data.{ErrorCode, PaginationResult}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.Errors.RawError
import com.advancedtelematic.libats.slick.db.DatabaseConfig
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.TargetsRole
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{SignedPayload, TargetName}
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import slick.jdbc.MySQLProfile.api.Database

case class AutoInstall(namespace: Namespace, pkgName: TargetName, device: DeviceId)

object AutoInstall {
  import com.advancedtelematic.libats.codecs.CirceAnyVal._
  import io.circe.generic.semiauto._
  import io.circe.{Decoder, Encoder}

  implicit val decoderAutoInstall: Decoder[AutoInstall] = deriveDecoder
  implicit val encoderAutoInstall: Encoder[AutoInstall] = deriveEncoder
}

case class PaginationRequest(limit: Long, offset: Long)

object AutoInstallMigrationApp extends BootApp with DatabaseConfig with Settings {
  override lazy val projectName = "director-migration"

  implicit val _db = db

  // we don't have the normal uri in Settings, only the binary one
  val reposerverUri = Uri(s"""${config.getString("tuf.binary.scheme")}://${config.getString("tuf.binary.host")}:${config.getString("tuf.binary.port")}""")
  val reposerver = new TargetsReposerverClient(reposerverUri)

  val coreClient = new CoreClient(coreUri)

  val migrater = args match {
    case Array("--run") => new RealMigrate(coreClient)
    case Array() => new DryRunMigrate
    case _ =>
      log.error("wrong arguments passed, should be of the form: [--run]")
      sys.exit(1)
  }

  Await.result(new AutoInstallMigration(reposerver, coreClient, migrater).run, Duration.Inf)

  log.info("Migration finished")

  system.terminate()
}

class Client(clientName: String, uri: Uri)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer)  {
  protected def apiUri(path: Path) =
    uri.withPath(Path("/api") / "v1" ++ Slash(path))

  protected def ClientError(msg: String) = RawError(ErrorCode(s"${clientName}_remote_error"), StatusCodes.BadGateway, msg)
  private val _http = Http()
  protected def execHttp[T : ClassTag](namespace: Namespace, request: HttpRequest)
                        (implicit um: FromEntityUnmarshaller[T]): Future[T] = {
    _http.singleRequest(request.withHeaders(RawHeader("x-ats-namespace", namespace.get))).flatMap {
      case r @ HttpResponse(status, _, _, _) if status.isSuccess() =>
        um(r.entity)
      case r =>
        FastFuture.failed(ClientError(s"Unexpected response from ${clientName}: $r"))
    }
  }
}

class CoreClient(coreUri: Uri)
                (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer)
    extends Client("core", coreUri)
{
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  val log = LoggerFactory.getLogger(this.getClass)

  def listAutoUpdates(ns: Namespace, paginationRequest: PaginationRequest): Future[PaginationResult[AutoInstall]] = {
    val req = HttpRequest(uri = apiUri(Path("auto_install_migration"))
                            .withQuery(Query("limit" -> paginationRequest.limit.toString,
                                             "offset" -> paginationRequest.offset.toString)))

    execHttp[PaginationResult[AutoInstall]](ns, req)
  }

  def deactivateAutoUpdate(ns: Namespace, device: DeviceId, targetName: TargetName): Future[Done] = {
    val req = HttpRequest(HttpMethods.DELETE, uri = apiUri(Path("auto_install") / targetName.value / device.show))
    execHttp[Unit](ns, req).map(_ => Done)
  }
}

class TargetsReposerverClient(reposerverUri: Uri)
                             (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer)
    extends Client("reposerver", reposerverUri)
{
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  def targets(ns: Namespace): Future[SignedPayload[TargetsRole]] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path("user_repo") / "targets.json"))

    execHttp[SignedPayload[TargetsRole]](ns, req)
  }
}

// the function action, should be somewhat referential transparant, at least monotonically defined
class Cache[K,V](action: K => Future[V])(implicit ec: ExecutionContext) {
  private val cache = new ConcurrentHashMap[K,V]()

  def find(key: K): Future[V] = {
    if (cache.contains(key)) {
      FastFuture.successful { cache.asScala(key) }
    } else {
      action(key).flatMap { case value =>
        FastFuture.successful{
          cache.put(key, value)
          value
        }
      }
    }
  }
}

trait Migrater {
  def migrate(ns: Namespace, device: DeviceId, primaryEcuSerial: EcuSerial, targetName: TargetName): Future[Done]
}

class DryRunMigrate extends Migrater {
  private val log = LoggerFactory.getLogger(this.getClass)

  override def migrate(ns: Namespace, device: DeviceId, primaryEcuSerial: EcuSerial, targetName: TargetName): Future[Done] = FastFuture.successful {
    log.info(s"!!! MIGRATE ($ns, $device, $primaryEcuSerial, $targetName)")
    Done
  }
}

class RealMigrate(coreClient: CoreClient)(implicit db: Database, ec: ExecutionContext) extends Migrater
    with AutoUpdateRepositorySupport
{
  override def migrate(ns: Namespace, device: DeviceId, primaryEcu: EcuSerial, targetName: TargetName): Future[Done] = {
      autoUpdateRepository.persist(ns, device, primaryEcu, targetName).flatMap{ _ =>
        coreClient.deactivateAutoUpdate(ns, device, targetName)
      }
    }
}

class AutoInstallMigration(reposerver: TargetsReposerverClient, coreClient: CoreClient, migrater: Migrater)
                          (implicit db: Database, ec: ExecutionContext, mat: Materializer)
    extends AdminRepositorySupport
    with RepoNameRepositorySupport
{
  val log = LoggerFactory.getLogger(this.getClass)

  val cache: Cache[Namespace, SignedPayload[TargetsRole]] = new Cache(reposerver.targets(_))

  type PaginationAction[T] = PaginationRequest => Future[PaginationResult[T]]

  def fromPagination[T](limit: Long, action: PaginationAction[T]): Source[T, NotUsed] = {
    type State = Option[PaginationResult[T]] // None means start

    def fetch(offset: Long): Future[Option[(State, T)]] = {
      val req = PaginationRequest(limit, offset)
      action(req).map { pag =>
        pag.values match {
          case Nil => None
          case x +: xs => Some((Some(pag.copy(values = xs)), x))
        }
      }
    }

    Source.unfoldAsync[State, T](None) {
      // first time
      case None => fetch(0)
      case Some(pag) =>
        pag.values match {
          case Nil => fetch(pag.offset + limit)
          case x +: xs => FastFuture.successful(Some((Some(pag.copy(values = xs)), x)))
        }
    }
  }

  def fetchPaginatedAutoInstall(ns: Namespace): PaginationAction[AutoInstall] = { case pagReq =>
    coreClient.listAutoUpdates(ns, pagReq)
  }

  // the primary ecu if device exists
  def deviceExistsInDirector(ns: Namespace, device: DeviceId): Future[Option[EcuSerial]] =
    adminRepository.getPrimaryEcuForDevice(device).map(Some.apply).recover {
      case DBErrors.DeviceMissingPrimaryEcu => None
    }

  def targetNameExists(ns: Namespace, targetName: TargetName): Future[Boolean] =
    cache.find(ns).map { sig =>
      sig.signed.targets.values.flatMap(_.custom.flatMap(_.hcursor.downField("name").as[TargetName].toOption)).toSet.contains(targetName)
    }

  val migrateOne: AutoInstall => Future[(Boolean,AutoInstall)] = {
    case ai@AutoInstall(ns, targetName, device) =>
      deviceExistsInDirector(ns, device).flatMap {
        case None =>
          log.debug(s"device ${device.show} not registered with director, skipping")
          FastFuture.successful((false, ai))
        case Some(primaryEcu) =>
          // so the device exists, does the targetName exists?
          targetNameExists(ns, targetName).flatMap {
            case false =>
              log.debug(s"targetName ${targetName.value} (for device ${device.show}) does not exists in the image repo, skipping")
              FastFuture.successful((false, ai))
            case true =>
              migrater.migrate(ns, device, primaryEcu, targetName).map(_ => (true, ai))
          }
      }
  }

  val migrateOneFlow: Flow[AutoInstall, (Boolean, AutoInstall), NotUsed] = Flow[AutoInstall].mapAsyncUnordered(3)(migrateOne)

  val sink: Sink[(Boolean, AutoInstall), Future[Done]] = Sink.foreach{case (migrated, AutoInstall(ns, device, targetName)) =>
    log.debug(s"Processed ($ns, $device, $targetName)")
  }

  def run: Future[Done] = {
    val nsSource: Source[Namespace, NotUsed] = repoNameRepository.streamNamespaces

    val source: Source[AutoInstall, NotUsed] = nsSource.flatMapConcat { ns =>
      fromPagination(50, fetchPaginatedAutoInstall(ns))
    }

    source.via(migrateOneFlow).runWith(sink)
  }
}
