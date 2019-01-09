package com.advancedtelematic.director.db

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKitBase
import com.advancedtelematic.libats.data.DataType
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, ValidEcuSerial}
import com.advancedtelematic.libats.test.{DatabaseSpec, LongTest}
import com.advancedtelematic.libtuf.data.TufDataType.{RSATufKey, ValidKeyId}
import org.scalatest.concurrent.{PatienceConfiguration, ScalaFutures}
import org.scalatest.{FunSuite, Matchers}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class EcuKeysToJsonEncodedMigrationSpec extends FunSuite with TestKitBase with DatabaseSpec with PatienceConfiguration with LongTest with ScalaFutures with DeviceRepositorySupport with Matchers {

  override implicit lazy val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)

  implicit val mat = ActorMaterializer()

  implicit val ec = ExecutionContext.Implicits.global

  val migration = new EcuKeysToJsonEncodedMigration()

  val ns = DataType.Namespace("migrationns")

  test("updates a key from old encoding to new encoding") {
    val device = DeviceId.generate()
    val serial = "cxJvjrQeNqwcct".refineTry[ValidEcuSerial].get

    val sql =
      sqlu"""insert into `ecus` (ecu_serial, device, namespace, `primary`, public_key, hardware_identifier) VALUES (
            '#${serial.value}',
            '#${device.uuid.toString}',
            '#${ns.get}',
            1,
            '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwfwpnCmgkCflw3MA8+bn\nI9JKjBxiHrQNCt1IkZxf7vMnrUumeuI9QTrgkLDrHC83gcWq9V3hBd4oCFSBXYa7\nr/00E7xXgSxiFRctWhQbOBRnH/Kqd+NcB6oYayJMoacsFB19Y45R/TxxkpyzZF2O\n9lWYju7nuFqSWv0sdoZ9a7vxisaAIUzflPscnTxeMN5TQSlnI5cltmLt8ZD1g1rn\nix2C5PaFaEgucL8qFfds6azbutP4gLu4b7iqSV7s+k1akP5ygr+6Yq4Bu6QFUOdn\nooLdiA5C5EvW6D5cPW+nSVmMgwWJ4EILCLlN8gIxWI/qbWpG/DXg2fTAGQ+GLaFm\nXQIDAQAB\n-----END PUBLIC KEY-----\n',
            'somehwid'
            );"""

    db.run(sql).futureValue

    migration.run.futureValue

    deviceRepository.findEcus(ns, device).futureValue.head.tufKey shouldBe a[RSATufKey]
  }

  test("migrations run with multiple keys") {
    val device = DeviceId.generate()

    val serial0 = "oneserial".refineTry[ValidEcuSerial].get

    val sql0 =
      sqlu"""insert into `ecus` (ecu_serial, device, namespace, `primary`, public_key, hardware_identifier) VALUES (
            '#${serial0.value}',
            '#${device.uuid.toString}',
            '#${ns.get}',
            1,
            '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxgmP1Ohroaz+FijPr/JP\nBfYvXHB3EmyqMTvr6IopMWyr2EZreUxVYOyvf+8KiktqsjOfRrnCf6UGiqTqhPQk\nzJnWaLGcIA45DrgzDX99V27eFhLXdRIcLaFyhVDc9CyS75Jz5krPkPnLO6MVDBOD\nxq7nG0zPBj6YV3IBrTR1uDjJAIKdTiHs+kLHLI6E1QdR7FYKXShfSd/9+xgtX35g\n1HIlG2FBxH9uIZDVIkOvy2/d8hjix6JwuETTQmVQHY/BAHctq9pJp+ukHxxkZtLw\ngGjhyBivkywdXF6vfYcER/cexFUni0Oa6YtWCj/Fl6Kp+yFBxxFhX3Go9D+jgecy\nUwIDAQAB\n-----END PUBLIC KEY-----',
            'somehwid'
            );"""

    val serial1 = "anotherserial".refineTry[ValidEcuSerial].get

    val sql1 =
      sqlu"""insert into `ecus` (ecu_serial, device, namespace, `primary`, public_key, hardware_identifier) VALUES (
            '#${serial1.value}',
            '#${device.uuid.toString}',
            '#${ns.get}',
            1,
            '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvcHcea881D6O7PyoymuC\nBG9pVw344h0rOvnWVxzMcQcpMj9dDwdRvDjLo0xLAbmB5/gOZizzkG2wLKAvzYec\naPz6fSZdmRPxvbWxKQo9InqYI0LMbiRxQt+85sjea4FKOZ3NPbzoTHBUvASGle1K\nTA3Mzo/TQCHwHwX+IfkVsrjtmdgVmgWtenGFjiKb7rhi1Z7GEOeB4WnfGZ3dAEek\ngegw8oY+iITqY/7K2lDBu8koH/LIEbb1gyMb/SnMnYM6g023Pl/KruRULn9PBVtv\nrUzBTlLX9C8AHI/iCkdGy1462TI/QHBxujGrYSVWpOpFpYy4xu+kJv4WhzqBX/yt\nHQIDAQAB\n-----END PUBLIC KEY-----',
            'somehwid'
            );"""

    db.run(DBIO.seq(sql0, sql1)).futureValue

    migration.run.futureValue

    deviceRepository.findEcus(ns, device).futureValue.map(_.tufKey.id.value) should contain("096d9548802470a5595ad1a54c8ae6e2b80dc7060ca5c2a91c7132573d109f6b")
    deviceRepository.findEcus(ns, device).futureValue.map(_.tufKey.id.value) should contain("734b8e46357c1e3c60bbfe2dd7bd57ffdf338cb55eba6f2c7756a5d36e92e88d")
  }
}
