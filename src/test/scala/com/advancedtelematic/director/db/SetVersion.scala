package com.advancedtelematic.director.db

//trait SetVersion {
//  def setCampaign(device: DeviceId, version: Int)
//                 (implicit val db: Database, val ec: ExecutionContext): Future[Done] = db.run {
//    (Schema.deviceTargets += DeviceUpdateTarget(device, None, None, version, false))
//      .map(_ => Done)
//  }
//
//  def setDeviceVersion(device: DeviceId, version: Int)
//                      (implicit val db: Database, val ec: ExecutionContext): Future[Done] = db.run {
//    (Schema.deviceCurrentTarget.insertOrUpdate(DeviceCurrentTarget(device, version)))
//      .map(_ => Done)
//  }
//}
