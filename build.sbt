name := "director"
organization := "com.advancedtelematic"
scalaVersion := "2.12.5"
licenses += ("MPL-2.0", url("http://mozilla.org/MPL/2.0/"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/advancedtelematic/director"),
    "scm:git:git@github.com:advancedtelematic/director.git"))

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-Ypartial-unification"
   )

resolvers += "ATS Releases" at "http://nexus.advancedtelematic.com:8081/content/repositories/releases"

resolvers += "ATS Snapshots" at "http://nexus.advancedtelematic.com:8081/content/repositories/snapshots"

libraryDependencies ++= {
  val akkaV = "2.5.25"
  val akkaHttpV = "10.1.10"
  val scalaTestV = "3.0.5"
  val bouncyCastleV = "1.57"
  val tufV = "0.7.0-61-g909b804"
  val libatsV = "0.3.0-83-g43409bd"
  val circeConfigV = "0.0.2"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "org.scalatest"     %% "scalatest" % scalaTestV % "test",

    "ch.qos.logback" % "logback-classic" % "1.1.3",

    "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",

    "com.advancedtelematic" %% "libats" % libatsV,
    "com.advancedtelematic" %% "libats-messaging" % libatsV,
    "com.advancedtelematic" %% "libats-messaging-datatype" % libatsV,
    "com.advancedtelematic" %% "libats-metrics-akka" % libatsV,
    "com.advancedtelematic" %% "libats-metrics-prometheus" % libatsV,
    "com.advancedtelematic" %% "libats-metrics-kafka" % libatsV,
    "com.advancedtelematic" %% "libats-http-tracing" % libatsV,
    "com.advancedtelematic" %% "libats-slick" % libatsV,
    "com.advancedtelematic" %% "libats-logging" % libatsV,
    "com.advancedtelematic" %% "libtuf" % tufV,
    "com.advancedtelematic" %% "libtuf-server" % tufV,
    "com.advancedtelematic" %% "circe-config" % circeConfigV,

    "org.bouncycastle" % "bcprov-jdk15on" % bouncyCastleV,
    "org.bouncycastle" % "bcpkix-jdk15on" % bouncyCastleV,

    "org.scala-lang.modules" %% "scala-async" % "0.9.6",

    "org.mariadb.jdbc" % "mariadb-java-client" % "2.2.1"
  )
}

scalacOptions in Compile ++= Seq(
  "-deprecation",
  "-feature",
  "-Xlog-reflective-calls",
  "-Xlint",
  "-Ywarn-unused-import",
  "-Ywarn-dead-code",
  "-Yno-adapted-args",
  "-Ypartial-unification"
)

scalacOptions in (Compile, console) ~= (_.filterNot(_ == "-Ywarn-unused-import"))

testOptions in Test ++= Seq(
  Tests.Argument(TestFrameworks.ScalaTest, "-u", "target/test-reports"),
  Tests.Argument(TestFrameworks.ScalaTest, "-oDS")
)

enablePlugins(BuildInfoPlugin)

buildInfoOptions += BuildInfoOption.ToMap

buildInfoOptions += BuildInfoOption.BuildTime


mainClass in Compile := Some("com.advancedtelematic.director.Boot")

import com.typesafe.sbt.packager.docker._

dockerRepository := Some("advancedtelematic")

packageName in Docker := packageName.value

dockerUpdateLatest := true

dockerAliases ++= Seq(dockerAlias.value.withTag(git.gitHeadCommit.value))

defaultLinuxInstallLocation in Docker := s"/opt/${moduleName.value}"

dockerCommands := Seq(
  Cmd("FROM", "advancedtelematic/alpine-jre:adoptopenjdk-jdk8u222"),
  ExecCmd("RUN", "mkdir", "-p", s"/var/log/${moduleName.value}"),
  Cmd("ADD", "opt /opt"),
  Cmd("WORKDIR", s"/opt/${moduleName.value}"),
  ExecCmd("ENTRYPOINT", s"/opt/${moduleName.value}/bin/${moduleName.value}"),
  Cmd("RUN", s"chown -R daemon:daemon /opt/${moduleName.value}"),
  Cmd("RUN", s"chown -R daemon:daemon /var/log/${moduleName.value}"),
  Cmd("USER", "daemon")
)

enablePlugins(JavaAppPackaging)

Versioning.settings

Release.settings

enablePlugins(Versioning.Plugin)

fork := true

sonarProperties ++= Map(
  "sonar.projectName" -> "OTA Connect Director",
  "sonar.projectKey" -> "ota-connect-director",
  "sonar.host.url" -> "http://sonar.in.here.com",
  "sonar.links.issue" -> "https://saeljira.it.here.com/projects/OTA/issues",
  "sonar.links.scm" -> "https://main.gitlab.in.here.com/olp/edge/ota/connect/back-end/director",
  "sonar.links.ci" -> "https://main.gitlab.in.here.com/olp/edge/ota/connect/back-end/director/pipelines",
  "sonar.projectVersion" -> version.value,
  "sonar.language" -> "scala")
