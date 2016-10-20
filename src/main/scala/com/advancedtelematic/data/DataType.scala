package com.advancedtelematic.data

import eu.timepit.refined.api.{Refined, Validate}
import org.genivi.sota.data.Namespace

object DataType {
  case class Blueprint(id: String, value: String)
}
