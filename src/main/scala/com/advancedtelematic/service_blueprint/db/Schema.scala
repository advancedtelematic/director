package com.advancedtelematic.service_blueprint.db

import slick.driver.MySQLDriver.api._

object Schema {
  import com.advancedtelematic.data.DataType._

  class BlueprintTable(tag: Tag) extends Table[Blueprint](tag, "blueprint") {
    def id = column[String]("id")
    def value = column[String]("value")

    override def * = (id, value) <> ((Blueprint.apply _).tupled, Blueprint.unapply)
  }

  protected [db] val blueprints = TableQuery[BlueprintTable]
}
