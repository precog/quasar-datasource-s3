import scala.collection.Seq

ThisBuild / scalaVersion := "2.12.10"

ThisBuild / githubRepository := "quasar-datasource-s3"

publishAsOSSProject in ThisBuild := true

homepage in ThisBuild := Some(url("https://github.com/precog/quasar-datasource-s3"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/precog/quasar-datasource-s3"),
  "scm:git@github.com:precog/quasar-datasource-s3.git"))

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)

val http4sVersion = "0.21.0-RC2"
val scalaXmlVersion = "1.1.0"

val catsEffectVersion = "2.0.0"
val shimsVersion = "2.0.0"
val specsVersion = "4.8.3"

lazy val core = project
  .in(file("datasource"))
  .settings(addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
  .settings(
    name := "quasar-datasource-s3",

    quasarPluginName := "s3",

    quasarPluginQuasarVersion := managedVersions.value("precog-quasar"),

    quasarPluginDatasourceFqcn := Some("quasar.physical.s3.S3DatasourceModule$"),

    /** Specify managed dependencies here instead of with `libraryDependencies`.
      * Do not include quasar libs, they will be included based on the value of
      * `datasourceQuasarVersion`.
      */
    quasarPluginDependencies ++= Seq(
      "org.slf4s"              %% "slf4s-api"           % "1.7.25",
      "org.http4s"             %% "http4s-scala-xml"    % http4sVersion,
      "org.http4s"             %% "http4s-async-http-client" % http4sVersion,
      "org.scala-lang.modules" %% "scala-xml"           % scalaXmlVersion,
      "com.codecommit"         %% "shims"               % shimsVersion,
      "org.typelevel"          %% "cats-effect"         % catsEffectVersion,
      "com.precog"             %% "quasar-foundation"   % managedVersions.value("precog-quasar") % Test classifier "tests",
      "org.http4s"             %% "http4s-dsl"          % http4sVersion % Test,
      "org.specs2"             %% "specs2-core"         % specsVersion % Test,
      "org.specs2"             %% "specs2-scalaz"       % specsVersion % Test,
      "org.specs2"             %% "specs2-scalacheck"   % specsVersion % Test
    ))
  .enablePlugins(QuasarPlugin)
