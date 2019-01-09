package com.advancedtelematic.director.db

import java.security.PublicKey

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.EcuSerial
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
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

  def writeKey(ecuSerial: EcuSerial, ns: Namespace, publicKey: PublicKey)(implicit db: Database): Future[TufKey] = {
    implicit val setRsaKey: SetParameter[TufKey] = (tufKey: TufKey, pp: PositionedParameters) => {
      pp.setString(tufKey.asJson.noSpaces)
    }

    val rsaKey = RSATufKey(publicKey)
    val sql = sqlu"""update `ecus` set public_key = $rsaKey where ecu_serial = '#${ecuSerial.value}' and namespace = '#${ns.get}'"""

    db.run(sql).flatMap {
      case 1 => FastFuture.successful(rsaKey)
      case _ => FastFuture.failed(new RuntimeException(s"Could not update key format. $ecuSerial $ns"))
    }
  }

  def existingKeys(implicit db: Database):  Source[(EcuSerial, Namespace, PublicKey), NotUsed] = {
    implicit val getResult: GetResult[(EcuSerial, Namespace, String)] = pr => {
      val ecuSerial = implicitly[ColumnType[EcuSerial]].getValue(pr.rs, 1)
      val namespace = implicitly[ColumnType[Namespace]].getValue(pr.rs, 2)
      (ecuSerial, namespace, pr.rs.getString("public_key"))
    }

    val findQ = sql"""select ecu_serial, namespace, public_key from `ecus`""".as[(EcuSerial, Namespace, String)]

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

