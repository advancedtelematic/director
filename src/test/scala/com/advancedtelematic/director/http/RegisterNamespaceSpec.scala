package com.advancedtelematic.director.http

//trait RegisterNamespaceSpec extends DirectorSpec
//    with Eventually
//    with DatabaseSpec
//    with DefaultPatience
//    with NamespacedRequests
//    with RouteResourceSpec
//    with RepoNameRepositorySupport {
//
//  private val timeout = Timeout(Span(5, Seconds))
//  private val interval = Interval(Span(200, Milliseconds))
//
//  test("creates root repository and root file for namespace") {
//    val createRepoWorker = new CreateRepoWorker(new DirectorRepo(keyserverClient), defaultKeyType.get)
//    val namespace = Namespace("defaultNS")
//
//    createRepoWorker.action(UserCreated(namespace.get))
//
//    eventually(timeout, interval) {
//      val repoId = repoNameRepository.getRepo(namespace).futureValue
//      repoId shouldBe a[RepoId]
//
//      val rootFile = keyserverClient.fetchRootRole(repoId).futureValue
//      rootFile.signed._type shouldBe "Root"
//      rootFile.signed.keys.head._2.keytype shouldBe defaultKeyType.get
//    }
//  }
//
//  testWithNamespace("creates repo using given key type") { implicit ns =>
//    createRepo(defaultKeyType.get)
//    fetchRootKeyType shouldBe defaultKeyType.get
//  }
//
//  testWithNamespace("creates repo using default key type") { implicit ns =>
//    Post(apiUri("admin/repo")).namespaced ~> routes ~> check {
//      status shouldBe StatusCodes.Created
//    }
//
//    fetchRootKeyType shouldBe RsaKeyType
//  }
//
//  testWithNamespace("creating repo fails for invalid key type parameter") { implicit ns =>
//    Post(apiUri("admin/repo"))
//        .withEntity(ContentTypes.`application/json`, """ { "keyType":"caesar" } """)
//        .namespaced ~> routes ~> check {
//      status shouldBe StatusCodes.BadRequest
//    }
//  }
//
//  testWithNamespace("push signed root") { implicit ns =>
//    Post(apiUri("admin/repo")).namespaced ~> routes ~> check {
//      status shouldBe StatusCodes.Created
//    }
//
//    val rootRole = RootRole(Map.empty, Map.empty, 2, Instant.now().plus(1, ChronoUnit.DAYS))
//    val signedPayload = JsonSignedPayload(Seq.empty, rootRole.asJson)
//
//    Post(apiUri("admin/repo/root"), signedPayload).namespaced ~> routes ~> check {
//      status shouldBe StatusCodes.OK
//    }
//  }
//
//}

