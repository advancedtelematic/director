package com.advancedtelematic.director.db

import java.security.PublicKey

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RSATufKey, TufKey}
import io.circe.syntax._
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.{GetResult, PositionedParameters, SetParameter}
import com.advancedtelematic.libats.slick.db.SlickAnyVal._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


class EcuKeysToJsonEncodedMigration(implicit
                                    val db: Database,
                                    val mat: Materializer,
                                    val system: ActorSystem,
                                    val ec: ExecutionContext) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  def writeKey(ecuId: EcuIdentifier, ns: Namespace, publicKey: PublicKey)(implicit db: Database): Future[TufKey] = {
    implicit val setRsaKey: SetParameter[TufKey] = (tufKey: TufKey, pp: PositionedParameters) => {
      pp.setString(tufKey.asJson.noSpaces)
    }

    val rsaKey = RSATufKey(publicKey)
    val sql = sqlu"""update `ecus` set public_key = $rsaKey where ecu_serial = '#${ecuId.value}' and namespace = '#${ns.get}'"""

    db.run(sql).flatMap {
      case 1 => FastFuture.successful(rsaKey)
      case _ => FastFuture.failed(new RuntimeException(s"Could not update key format. $ecuId $ns"))
    }
  }

  def existingKeys(implicit db: Database):  Source[(EcuIdentifier, Namespace, PublicKey), NotUsed] = {
    implicit val getResult: GetResult[(EcuIdentifier, Namespace, String)] = pr => {
      val ecuId = implicitly[ColumnType[EcuIdentifier]].getValue(pr.rs, 1)
      val namespace = implicitly[ColumnType[Namespace]].getValue(pr.rs, 2)
      (ecuId, namespace, pr.rs.getString("public_key"))
    }

    val findQ = sql"""select ecu_serial, namespace, public_key from `ecus`""".as[(EcuIdentifier, Namespace, String)]

    Source.fromPublisher(db.stream(findQ)).mapConcat {
      case (serial, ns, pubkeyStr) =>
        TufCrypto.parsePublicPem(pubkeyStr) match {
          case Success(k) => Vector((serial, ns, k))
          case Failure(err) =>
            _log.error(s"Could not parse public key for $serial", err)
            Vector.empty
        }
    }
  }

  def run: Future[Done] = {
    val source = existingKeys.mapAsync(1)((writeKey _).tupled)

    source.runWith(Sink.foreach { key =>
      _log.info(s"Converted ${key.id} to new format")
    })
  }
}

