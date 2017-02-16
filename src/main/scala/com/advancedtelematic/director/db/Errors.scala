package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.DataType.{EcuTarget, FileCacheRequest, Snapshot}
import org.genivi.sota.data.Namespace

import org.genivi.sota.http.Errors.{EntityAlreadyExists, MissingEntity}

object Errors {
  val ConflictingFileCacheRequest = EntityAlreadyExists(classOf[FileCacheRequest])
  val MissingFileCacheRequest = MissingEntity(classOf[FileCacheRequest])

  val MissingNamespaceRepo = MissingEntity(classOf[Namespace])

  val MissingSnapshot = MissingEntity(classOf[Snapshot])
  val ConflictingSnapshot = EntityAlreadyExists(classOf[Snapshot])

  val ConflictingTarget = EntityAlreadyExists(classOf[EcuTarget])
  val MissingTarget = MissingEntity(classOf[EcuTarget])
}
