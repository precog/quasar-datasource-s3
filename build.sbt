import scala.collection.Seq

publishAsOSSProject in ThisBuild := true

homepage in ThisBuild := Some(url("https://github.com/slamdata/quasar-datasource-s3"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/quasar-datasource-s3"),
  "scm:git@github.com:slamdata/quasar-datasource-s3.git"))

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)

val quasarVersion = IO.read(file("./quasar-version")).trim

val http4sVersion = "0.20.0-M5"
val scalaXmlVersion = "1.1.0"

val catsEffectVersion = "1.0.0"
val shimsVersion = "1.7.0"
val specsVersion = "4.1.2"

lazy val core = project
  .in(file("datasource"))
  .settings(addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"))
  .settings(
    name := "quasar-datasource-s3",

    datasourceName := "s3",

    datasourceQuasarVersion := quasarVersion,

    datasourceModuleFqcn := "quasar.physical.s3.S3DatasourceModule$",

    /** Specify managed dependencies here instead of with `libraryDependencies`.
      * Do not include quasar libs, they will be included based on the value of
      * `datasourceQuasarVersion`.
      */
    datasourceDependencies ++= Seq(
      "org.http4s"             %% "http4s-scala-xml"    % http4sVersion,
      "org.http4s"             %% "http4s-blaze-client" % http4sVersion,
      "org.scala-lang.modules" %% "scala-xml"           % scalaXmlVersion,
      "com.codecommit"         %% "shims"               % shimsVersion,
      "org.typelevel"          %% "cats-effect"         % catsEffectVersion,
      "com.slamdata"           %% "quasar-foundation"   % quasarVersion % Test classifier "tests",
      "org.http4s"             %% "http4s-dsl"          % http4sVersion % Test,
      "org.specs2"             %% "specs2-core"         % specsVersion % Test,
      "org.specs2"             %% "specs2-scalaz"       % specsVersion % Test,
      "org.specs2"             %% "specs2-scalacheck"   % specsVersion % Test
    ))
  .enablePlugins(AutomateHeaderPlugin, DatasourcePlugin)
