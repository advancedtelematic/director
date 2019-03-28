package com.advancedtelematic.director.repo


//class DirectorRepoSpec
//    extends DirectorSpec
//    with DefaultPatience
//{
//
//  val dirNs = Namespace("director-repo-spec")
//
//  val countKeyserverClient = new FakeKeyserverClient()
//  val directorRepo = new DirectorRepo(countKeyserverClient)
//
//  test("Only create repo once") {
//    countKeyserverClient.count shouldBe 0
//    val repoId = directorRepo.findOrCreate(dirNs, Ed25519KeyType).futureValue
//    countKeyserverClient.count shouldBe 1
//    val repoId2 = directorRepo.findOrCreate(dirNs, Ed25519KeyType).futureValue
//
//    countKeyserverClient.count shouldBe 1
//    repoId shouldBe repoId2
//  }
//
//}
