import com.typesafe.sbt.packager.docker._
import sbt.Keys._
import sbt._
import com.typesafe.sbt.SbtNativePackager.Docker
import DockerPlugin.autoImport._
import com.typesafe.sbt.SbtNativePackager.autoImport._
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.packager.docker._

scalaVersion := "2.12.5"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-Ypartial-unification")
scalacOptions in (Compile, console) ~= (_.filterNot(_ == "-Ywarn-unused-import"))
scalacOptions in Compile ++= Seq(
  "-deprecation",
  "-feature",
  "-Xlog-reflective-calls",
  "-Xlint",
  // "-Ywarn-unused-import",
  "-Ywarn-dead-code",
  "-Yno-adapted-args",
  "-Ypartial-unification"
)
organization := "com.advancedtelematic"
resolvers += "ATS Releases" at "http://nexus.advancedtelematic.com:8081/content/repositories/releases"
resolvers += "ATS Snapshots" at "http://nexus.advancedtelematic.com:8081/content/repositories/snapshots"
testOptions in Test ++= Seq(
  Tests.Argument(TestFrameworks.ScalaTest, "-u", "target/test-reports"),
  Tests.Argument(TestFrameworks.ScalaTest, "-oDS")
)
buildInfoOptions += BuildInfoOption.ToMap
buildInfoOptions += BuildInfoOption.BuildTime

Versioning.settings

val scalaTestV = "3.0.0"
val akkaV = "2.5.9"
val akkaHttpV = "10.0.11"
val bouncyCastleV = "1.57"
val circeConfigV = "0.0.2"
val tufV = "0.4.0-54-g317d7c8"
val libatsV = "0.1.2-18-gdfb0eb3"

libraryDependencies ++= {
  Seq(
    "com.advancedtelematic" %% "libats" % libatsV,
    "com.advancedtelematic" %% "libats" % libatsV,
    "com.advancedtelematic" %% "libats-http" % libatsV,
    "com.advancedtelematic" %% "libats-messaging" % libatsV,
    "com.advancedtelematic" %% "libats-messaging-datatype" % libatsV,
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "org.scala-lang.modules" %% "scala-async" % "0.9.6",
    "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",
    "org.scalatest" %% "scalatest" % scalaTestV % "test",
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test",
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.advancedtelematic" %% "circe-config" % circeConfigV
  )
}

libraryDependencies ++= {
  Seq(
    "com.advancedtelematic" %% "libats-metrics-akka" % libatsV,
    "com.advancedtelematic" %% "libats-metrics-prometheus" % libatsV,
    "com.advancedtelematic" %% "libats-slick" % libatsV,
    "com.advancedtelematic" %% "libtuf" % tufV,
    "com.advancedtelematic" %% "libtuf-server" % tufV,
    "com.advancedtelematic" %% "libats-slick" % libatsV,
    "org.bouncycastle" % "bcprov-jdk15on" % bouncyCastleV,
    "org.bouncycastle" % "bcpkix-jdk15on" % bouncyCastleV,
    "org.mariadb.jdbc" % "mariadb-java-client" % "2.2.1"
  )
}

enablePlugins(BuildInfoPlugin, Versioning.Plugin, JavaAppPackaging)

name := "director"

buildInfoOptions += BuildInfoOption.ToMap
buildInfoOptions += BuildInfoOption.BuildTime

Publish.disable
Release.settings

fork := true

mainClass in Compile := Some("com.advancedtelematic.director.Boot")

dockerRepository := Some("advancedtelematic")

packageName in Docker := packageName.value

dockerUpdateLatest := true

defaultLinuxInstallLocation in Docker := s"/opt/${moduleName.value}"

dockerCommands := Seq(
  Cmd("FROM", "alpine:3.6"),
  Cmd("RUN", "apk upgrade --update && apk add --update openjdk8-jre bash coreutils"),
  ExecCmd("RUN", "mkdir", "-p", s"/var/log/${moduleName.value}"),
  Cmd("ADD", "opt /opt"),
  Cmd("WORKDIR", s"/opt/${moduleName.value}"),
  ExecCmd("ENTRYPOINT", s"/opt/${moduleName.value}/bin/${moduleName.value}"),
  Cmd("RUN", s"chown -R daemon:daemon /opt/${moduleName.value}"),
  Cmd("RUN", s"chown -R daemon:daemon /var/log/${moduleName.value}"),
  Cmd("USER", "daemon")
)
