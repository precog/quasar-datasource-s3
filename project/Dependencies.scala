package quasar.s3.project

import scala.Boolean
import scala.collection.Seq

import sbt._

object Dependencies {
  private val http4sVersion = "0.18.13"

  // hooray, scala-xml. we use it for parsing XML out of
  // the S3 API's responses.
  private val scalaXmlVersion = "1.1.0"

  // used for parsing JSON out of the stream of S3 data
  // in an object.
  // we need to be compatible with Quasar's version of both
  // fs2 and jawn, so we use the older circe-jawn version.
  private val quasarVersion = IO.read(file("./quasar-version")).trim
  private val circeJawnVersion = "0.9.3"
  private val fs2Version = "0.10.5"
  private val specsVersion = "4.2.0"
  private val shimsVersion = "1.3.0"
  private val argonautVersion = "6.2"
  private val catsEffectVersion = "0.10.1"
  private val circeFs2Version = "0.9.0"

  // http4s-blaze-client's version has to be in sync with
  // quasar's http4s version. The same goes for any
  // dependencies, transitive or otherwise.
  def lwcCore = Seq(
    "org.http4s"             %% "http4s-scala-xml"    % http4sVersion,
    "org.http4s"             %% "http4s-blaze-client" % http4sVersion,
    "org.scala-lang.modules" %% "scala-xml"           % scalaXmlVersion,
    "io.circe"               %% "circe-jawn"          % circeJawnVersion,
    "com.codecommit"         %% "shims-effect"        % shimsVersion,
    "org.typelevel"          %% "cats-effect"         % catsEffectVersion,
    "org.specs2"             %% "specs2-core"         % specsVersion % Test,
    "org.specs2"             %% "specs2-scalaz"       % specsVersion % Test,
    "org.specs2"             %% "specs2-scalacheck"   % specsVersion % Test,
    "io.argonaut"            %% "argonaut"            % argonautVersion,
    "io.circe"               %% "circe-fs2"           % circeFs2Version
  )

  // we need to separate quasar out from the LWC dependencies,
  // to keep from packaging it and its dependencies.
  // TODO: we should do this in the assembly routine.
  def lwc = lwcCore ++ Seq(
    "com.slamdata" %% "quasar-api-internal"        % quasarVersion,
    "com.slamdata" %% "quasar-api-internal"        % quasarVersion % Test classifier "tests",
    "com.slamdata" %% "quasar-foundation-internal" % quasarVersion,
    "com.slamdata" %% "quasar-foundation-internal" % quasarVersion % Test classifier "tests",
    "com.slamdata" %% "quasar-connector-internal"  % quasarVersion,
  )
}
