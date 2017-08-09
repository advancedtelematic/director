
import com.typesafe.sbt.SbtNativePackager.Docker
import sbt.Keys._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.ReleasePlugin.autoImport._

object Release {

  lazy val settings = {
    Seq(
      releaseProcess := Seq(
        checkSnapshotDependencies,
        ReleaseStep(releaseStepTask(publish in Docker))
      ),
      releaseIgnoreUntrackedFiles := true
    )
  }
}
