package quasar.s3.project

import scala.Boolean
import scala.collection.Seq

import sbt._

object Dependencies {
  private val http4sVersion = "0.20.0-M3"

  // hooray, scala-xml. we use it for parsing XML out of
  // the S3 API's responses.
  private val scalaXmlVersion = "1.1.0"

  private val catsEffectVersion = "1.0.0"
  private val quasarVersion = IO.read(file("./quasar-version")).trim
  private val shimsVersion = "1.2.1"
  private val specsVersion = "4.1.2"

  // http4s-blaze-client's version has to be in sync with
  // quasar's http4s version. The same goes for any
  // dependencies, transitive or otherwise.
  def datasourceCore = Seq(
    "org.http4s"             %% "http4s-scala-xml"    % http4sVersion,
    "org.http4s"             %% "http4s-blaze-client" % http4sVersion,
    "org.http4s"             %% "http4s-dsl"          % http4sVersion % Test,
    "org.scala-lang.modules" %% "scala-xml"           % scalaXmlVersion,
    "com.codecommit"         %% "shims"               % shimsVersion,
    "org.typelevel"          %% "cats-effect"         % catsEffectVersion,
    "org.specs2"             %% "specs2-core"         % specsVersion % Test,
    "org.specs2"             %% "specs2-scalaz"       % specsVersion % Test,
    "org.specs2"             %% "specs2-scalacheck"   % specsVersion % Test
  )

  // we need to separate quasar out from the datasource dependencies,
  // to keep from packaging it and its dependencies. TODO: we should
  // do this in the assembly routine.
  def datasource = datasourceCore ++ Seq(
    "com.slamdata" %% "quasar-api-internal"        % quasarVersion,
    "com.slamdata" %% "quasar-api-internal"        % quasarVersion % Test classifier "tests",
    "com.slamdata" %% "quasar-foundation-internal" % quasarVersion,
    "com.slamdata" %% "quasar-foundation-internal" % quasarVersion % Test classifier "tests",
    "com.slamdata" %% "quasar-connector-internal"  % quasarVersion,
    "com.slamdata" %% "quasar-connector-internal"  % quasarVersion % Test classifier "tests",
  )
}
