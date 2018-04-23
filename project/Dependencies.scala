package quasar.s3.project

import scala.Boolean
import scala.collection.Seq

import sbt._

object Dependencies {
  private val http4sVersion       = "0.16.6a"
  // For unknown reason sbt-slamdata's specsVersion, 3.8.7,
  // leads to a ParquetRDDE failure under a full test run
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
    "org.http4s"       %% "http4s-blaze-client" % http4sVersion       % Test,
    "io.verizon.knobs" %% "core"                % "4.0.30-scalaz-7.2" % Test
  )
}
