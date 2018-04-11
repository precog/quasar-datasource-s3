package quasar.project

import scala.Boolean
import scala.collection.Seq

import sbt._

object Dependencies {
  private val algebraVersion      = "0.7.0"
  private val argonautVersion     = "6.2"
  private val disciplineVersion   = "0.7.2"
  private val doobieVersion       = "0.4.4"
  private val jawnVersion         = "0.10.4"
  private val jacksonVersion      = "2.4.4"
  private val matryoshkaVersion   = "0.18.3"
  private val monocleVersion      = "1.4.0"
  private val pathyVersion        = "0.2.11"
  private val raptureVersion      = "2.0.0-M9"
  private val refinedVersion      = "0.8.3"
  private val scodecBitsVersion   = "1.1.2"
  private val http4sVersion       = "0.16.6a"
  private val scalacheckVersion   = "1.13.4"
  private val scalazVersion       = "7.2.18"
  private val scalazStreamVersion = "0.8.6a"
  private val scoptVersion        = "3.5.0"
  private val shapelessVersion    = "2.3.2"
  private val simulacrumVersion   = "0.10.0"
  // For unknown reason sbt-slamdata's specsVersion, 3.8.7,
  // leads to a ParquetRDDE failure under a full test run
  private val specsVersion        = "4.0.2"
  private val spireVersion        = "0.14.1"
  private val deloreanVersion     = "1.2.42-scalaz-7.2"
  private val fs2Version          = "0.9.6"
  private val fs2ScalazVersion    = "0.2.0"
  private val scalaXmlVersion     = "1.1.0"

  private val circeFs2Version     = "0.9.0"

  private val quasarVersion       = "38.2.3-f3f05e7"

  def lwc = Seq(
    "com.slamdata"               %% "slamdata-predef"           % "0.0.4",
    "org.scalaz"                 %% "scalaz-core"               % scalazVersion,
    "org.scalaz"                 %% "scalaz-concurrent"         % scalazVersion,
    "org.scalaz.stream"          %% "scalaz-stream"             % scalazStreamVersion,
    "com.github.julien-truffaut" %% "monocle-core"              % monocleVersion,
    "org.typelevel"              %% "algebra"                   % algebraVersion,
    "org.typelevel"              %% "spire"                     % spireVersion,
    "io.argonaut"                %% "argonaut"                  % argonautVersion,
    "io.argonaut"                %% "argonaut-scalaz"           % argonautVersion,
    "com.slamdata"               %% "matryoshka-core"           % matryoshkaVersion,
    "com.slamdata"               %% "pathy-core"                % pathyVersion,
    "com.slamdata"               %% "pathy-argonaut"            % pathyVersion,
    "eu.timepit"                 %% "refined"                   % refinedVersion,
    "com.chuusai"                %% "shapeless"                 % shapelessVersion,
    "org.scalacheck"             %% "scalacheck"                % scalacheckVersion,
    "com.propensive"             %% "contextual"                % "1.0.1",
    "com.github.mpilquist"       %% "simulacrum"                % simulacrumVersion                    % Test,
    "org.typelevel"              %% "algebra-laws"              % algebraVersion                       % Test,
    "org.typelevel"              %% "discipline"                % disciplineVersion                    % Test,
    "org.typelevel"              %% "spire-laws"                % spireVersion                         % Test,
    "org.specs2"                 %% "specs2-core"               % specsVersion                         % Test,
    "org.specs2"                 %% "specs2-scalacheck"         % specsVersion                         % Test,
    "org.scalaz"                 %% "scalaz-scalacheck-binding" % (scalazVersion + "-scalacheck-1.13") % Test,
    "org.typelevel"              %% "shapeless-scalacheck"      % "0.6.1"                              % Test,
    "org.typelevel"              %% "scalaz-specs2"             % "0.5.2"                              % Test,
    "com.github.julien-truffaut" %% "monocle-macro"             % monocleVersion,
    "org.scala-lang.modules"     %% "scala-parser-combinators"  % "1.0.6",
    "org.typelevel"              %% "algebra-laws"              % algebraVersion                       % Test,
    "co.fs2"                     %% "fs2-core"                  % fs2Version,
    "co.fs2"                     %% "fs2-io"                    % fs2Version,
    "co.fs2"                     %% "fs2-scalaz"                % fs2ScalazVersion,
    "com.github.scopt"           %% "scopt"                     % scoptVersion,
    "org.spire-math"             %% "jawn-parser"               % jawnVersion,
    "com.github.scopt"           %% "scopt"                     % scoptVersion,
    "org.jboss.aesh"              % "aesh"                      % "0.66.17",
    "org.http4s"                 %% "http4s-dsl"                % http4sVersion,
    "org.http4s"                 %% "http4s-argonaut"           % http4sVersion,
    "org.http4s"                 %% "http4s-client"             % http4sVersion,
    "org.http4s"                 %% "http4s-server"             % http4sVersion,
    "org.http4s"                 %% "http4s-scala-xml"          % http4sVersion,
    "org.http4s"                 %% "http4s-blaze-server"       % http4sVersion,
    "org.http4s"                 %% "http4s-blaze-client"       % http4sVersion,
    "eu.timepit"                 %% "refined-scalacheck"        % refinedVersion                       % Test,
    "org.quasar-analytics"       %% "quasar-mimir-internal"     % quasarVersion,
    "org.scala-lang.modules"     %% "scala-xml"                 % scalaXmlVersion,
    "io.circe"                   %% "circe-fs2"                 % circeFs2Version
  )

  def it = lwc ++ Seq(
    "io.argonaut"      %% "argonaut-monocle"    % argonautVersion     % Test,
    "org.http4s"       %% "http4s-blaze-client" % http4sVersion       % Test,
    "eu.timepit"       %% "refined-scalacheck"  % refinedVersion      % Test,
    "io.verizon.knobs" %% "core"                % "4.0.30-scalaz-7.2" % Test
  )
}
