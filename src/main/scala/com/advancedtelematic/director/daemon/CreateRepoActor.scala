package com.advancedtelematic.director.daemon

import akka.Done
import com.advancedtelematic.director.db.Errors.ConflictNamespaceRepo
import com.advancedtelematic.director.repo.DirectorRepo
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.Messages.UserCreated
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}

class CreateRepoWorker(directorRepo: DirectorRepo)(implicit ec: ExecutionContext) {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def action(uc: UserCreated): Future[Done] = {
    val namespace = Namespace(uc.id)
    directorRepo.findOrCreate(namespace).map(_ => Done).recover {
      case ConflictNamespaceRepo =>
        _log.info(s"The namespace $namespace already exists, skipping creation")
        Done
    }
  }
}
