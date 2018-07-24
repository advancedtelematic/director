import java.net.URI

import sbt.Keys._
import sbt._

object Publish {
  lazy val disable = Seq(
    publishArtifact := false,
    publish := (),
    publishLocal := ()
  )
}
