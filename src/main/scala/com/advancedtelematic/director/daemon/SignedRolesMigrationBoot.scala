package com.advancedtelematic.director.daemon

import akka.actor.Scheduler
import akka.http.scaladsl.server.Directives
import com.advancedtelematic.director.db.SignedRoleMigration
import com.advancedtelematic.director.{Settings, VersionInfo}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.slick.db.DatabaseConfig

import scala.concurrent.Await
import scala.concurrent.duration.Duration


object SignedRolesMigrationBoot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with DatabaseConfig {

  implicit val _db = db
  implicit val scheduler: Scheduler = system.scheduler

  val migrationF = new SignedRoleMigration().run.map { res =>
    log.info(s"Migration finished $res")
  }

  Await.result(migrationF, Duration.Inf)
}
