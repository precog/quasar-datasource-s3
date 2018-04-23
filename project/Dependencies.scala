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

  private val circeJawnVersion     = "0.8.0"

  private val quasarVersion       = "38.2.3-f3f05e7"

  def lwcCore = Seq(
    "org.http4s"                 %% "http4s-scala-xml"          % http4sVersion,
    "org.http4s"                 %% "http4s-blaze-client"       % http4sVersion,
    "org.scala-lang.modules"     %% "scala-xml"                 % scalaXmlVersion,
    "io.circe"                   %% "circe-jawn"                % circeJawnVersion
  )

  def lwc = lwcCore ++ Seq(
    "org.quasar-analytics"       %% "quasar-mimir-internal"     % quasarVersion
  )

  def it = lwc ++ Seq(
    "io.argonaut"      %% "argonaut-monocle"    % argonautVersion     % Test,
    "org.http4s"       %% "http4s-blaze-client" % http4sVersion       % Test,
    "eu.timepit"       %% "refined-scalacheck"  % refinedVersion      % Test,
    "io.verizon.knobs" %% "core"                % "4.0.30-scalaz-7.2" % Test
  )
}
