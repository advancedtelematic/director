package com.advancedtelematic.director.manifest

import cats.data.Xor
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.SignatureMethod.RSASSA_PSS
import com.advancedtelematic.director.data.RefinedUtils._
import com.advancedtelematic.director.util.DirectorSpec
import io.circe.parser._
import io.circe.syntax._
import java.time.Instant
// import org.genivi.sota.marshalling.CirceMarshallingSupport._

class ManifestJsonSpec extends DirectorSpec {

  val ecu_manifest_sample: String = """{"signatures": [{"method": "rsassa-pss", "sig": "df043006d4322a386cf85a6761a96bb8c92b2a41f4a4201badb8aae6f6dc17ef930addfa96a3d17f20533a01c158a7a33e406dd8291382a1bbab772bd2fa9804", "keyid": "49309f114b857e4b29bfbff1c1c75df59f154fbc45539b2eb30c8a867843b2cb"}], "signed": {"timeserver_time": "2016-10-14T16:06:03Z", "installed_image": {"filepath": "/file2.txt", "fileinfo": {"hashes": {"sha256": "3910b632b105b1e03baa9780fc719db106f2040ebfe473c66710c7addbb2605a", "sha512": "e2ebe151d7f357fcc6b0789d9e029bbf13310e98bc4d15585c1e90ea37c2c7181306f834342080ef007d71439bdd03fb728186e6d1e9eb51fdddf16f76301cef"}, "length": 21}}, "previous_timeserver_time": "2016-10-14T16:06:03Z", "ecu_serial": "ecu11111", "attacks_detected": ""}}"""

  val ecu_manifest_sample_parsed: SignedPayload[EcuManifest]
    = SignedPayload(
      signatures = Vector(ClientSignature(
                            method = RSASSA_PSS,
                            sig = "df043006d4322a386cf85a6761a96bb8c92b2a41f4a4201badb8aae6f6dc17ef930addfa96a3d17f20533a01c158a7a33e406dd8291382a1bbab772bd2fa9804".refineTry[ValidHexString].get,
                            keyid = "49309f114b857e4b29bfbff1c1c75df59f154fbc45539b2eb30c8a867843b2cb".refineTry[ValidKeyId].get)),
      signed = EcuManifest(timeserver_time = Instant.ofEpochSecond(1476461163),
                           installed_image = InstalledImage(
                             filepath = "/file2.txt",
                             fileinfo = FileInfo(
                               hashes = Hashes(
                                 sha256 = "3910b632b105b1e03baa9780fc719db106f2040ebfe473c66710c7addbb2605a".refineTry[ValidSha256].get,
                                 sha512 = "e2ebe151d7f357fcc6b0789d9e029bbf13310e98bc4d15585c1e90ea37c2c7181306f834342080ef007d71439bdd03fb728186e6d1e9eb51fdddf16f76301cef".refineTry[ValidSha512].get),
                               length = 21)),
                             previous_timeserver_time = Instant.ofEpochSecond(1476461163),
                           ecu_serial = "ecu11111".refineTry[ValidEcuSerial].get,
                           attacks_detected = ""))

  test("EcuManifest decodes correctly") {
    decode[SignedPayload[EcuManifest]](ecu_manifest_sample) shouldBe Xor.Right(ecu_manifest_sample_parsed)
  }

  test("EcuManifest encodes correctly") {
    parse(ecu_manifest_sample) shouldBe Xor.Right(ecu_manifest_sample_parsed.asJson)
  }
}
