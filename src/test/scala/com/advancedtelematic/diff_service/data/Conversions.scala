package com.advancedtelematic.diff_service.data

import com.advancedtelematic.diff_service.client.DiffServiceDirectorClient
import com.advancedtelematic.diff_service.util.DiffServiceSpec
import com.advancedtelematic.libats.data.RefinedUtils._
import com.advancedtelematic.libats.messaging_datatype.DataType.{Checksum, HashMethod, ValidCommit, ValidChecksum}

class ConversionsSpec extends DiffServiceSpec {

  test("can convert checksum to commit for ostree") {
    val underlying = "8d30e91162db8d61b0b3fac27529a128fdabcee077632c2727d2e6c0a98a1f70"
    val checksum = Checksum(HashMethod.SHA256, underlying.refineTry[ValidChecksum].get)
    val commit = underlying.refineTry[ValidCommit].get
    DiffServiceDirectorClient.convertChecksumToCommit(checksum) shouldBe commit
  }
}
