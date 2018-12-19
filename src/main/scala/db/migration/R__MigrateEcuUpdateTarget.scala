package db.migration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.director.db.EcuUpdateAssignmentMigration
import com.advancedtelematic.libats.slick.db.AppMigration
import slick.jdbc.MySQLProfile.api._

class R__MigrateEcuUpdateAssignment extends AppMigration  {

  implicit val system = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  override def migrate(implicit db: Database) = new EcuUpdateAssignmentMigration().run.map(_ => ())
}
