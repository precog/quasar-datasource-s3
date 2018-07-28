package quasar.s3.project

import coursier._
import java.nio.file.{Path, Files}
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.{CREATE_NEW, TRUNCATE_EXISTING}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.sys.process._
import java.util.stream.Collectors
import java.lang.Runtime

import io.circe.Json
import sbt.{Cache => _, MavenRepository => _, Tags => _, Task => _, _}
import sbt.Keys._
import scalaz._, Scalaz._
import Tags.Parallel
import scalaz.concurrent.Task
import Task._

object AssembleDatasource {
  val assembleDatasource = TaskKey[Unit]("assembleDatasource")

  // SBT needs `ModuleId` to declare dependencies with
  // `libraryDependencies`, and coursier wants `Dependency`.
  // Converting is straightforward but requires knowing the
  // `scalaVersion`; I've hard-coded it to 2.12 here.
  def moduleIdToDependency(moduleId: ModuleID): Dependency =
    Dependency(Module(moduleId.organization, moduleId.name + "_2.12"), moduleId.revision)

  val setAssemblyKey =
    assembleDatasource  in Compile := {
      // the location of the datasource jar itself. we make sure
      // it's been built by calling `package` here.
      val packagedJarFile = (sbt.Keys.`package` in Compile).value

      // where all of the artifacts for the project are.
      // notably the datasource jar, and later on the assembled
      // datasource tarball.
      val buildOutputFolder = (crossTarget in Compile).value

      // Grab the version to make it part of the tarball filename.
      val thisVersion = (version in ThisBuild).value

      // we assemble every component of the final tarball
      // in this folder, to be exploded over the user's
      // `plugins` folder.
      val datasourcePluginsFolder = new File(buildOutputFolder, "plugins")

      // a folder for just the datasource jar itself, shared by
      // other datasources in the same `plugins` folder.
      val datasourceJarFolder = new File(datasourcePluginsFolder, "datasource")

      // the jar file's own path.
      val datasourceJarFile = new File(datasourceJarFolder, packagedJarFile.name)

      // the datasource jar's path relative to datasourcePluginsFolder;
      // included in the generated .plugin file to let quasar
      // know where to load it from.
      val relativeDatasourceJarPath =
        datasourcePluginsFolder.toPath.relativize(datasourceJarFile.toPath).toString

      // the path to the generated .plugin file.
      val pluginFilePath = new File(datasourcePluginsFolder, "s3.plugin").toPath

      // start coursier on resolving all of the datasource's
      // dependencies, *except* for quasar. quasar and its
      // dependencies are already present in the user's
      // `plugins` folder.
      val resolution =
        Resolution(Dependencies.datasourceCore.map(moduleIdToDependency).toSet)

      // we're using datasourcePluginsFolder as a coursier cache while fetching
      // our dependencies, because that's the format of a `plugins` folder.
      val cache = Cache.fetch(
        datasourcePluginsFolder,
        CachePolicy.Update)

      val quasarVersion = IO.read(file("./quasar-version")).trim

      // I don't want to add kind-projector to  the compiler
      // plugins, so I'm hard-coding this type alias.
      // later on we're going to make a `List[FileError \/ A]`
      // into a `Validation[NonEmptyList[FileError], List[A]]`
      type OrFileErrors[A] = FileError ValidationNel A

      (for {
        // make the output plugins folder and the folder inside
        // which houses the datasource jar.
        _ <- Task.delay {
          datasourcePluginsFolder.mkdir()
          datasourceJarFolder.mkdir()
        }

        _ <- Task.delay(println("Fetching artifacts with coursier..."))

        // coursier prefers that we fetch metadata before fetching
        // artifacts. we do that in parallel with copying the datasource
        // jar to its new place, because they don't depend on one
        // another.
        fetchedJarFiles <-
          Parallel.unwrap(
            Applicative[ParallelTask].apply2(
              Parallel(
                Task(Files.copy(packagedJarFile.toPath(), datasourceJarFile.toPath(), REPLACE_EXISTING))
              ),
              Parallel(
                for {
                  metadata <- resolution.process.run(
                    // we don't use ~/.ivy2/local here because
                    // I've heard that coursier doesn't copy
                    // from local caches into other local caches.
                    Fetch.from(Seq(MavenRepository("https://repo1.maven.org/maven2")), cache)
                  )
                  // fetch artifacts in parallel into cache
                  artifactsPar = metadata.artifacts.toList
                    .traverse[ParallelTask, FileError \/ File] { f =>
                      Parallel(Cache.file(f, datasourcePluginsFolder, CachePolicy.Update).run)
                    }

                  // some contortions to make sure *all* errors
                  // are reported when any fail to download.
                  artifacts <- Parallel.unwrap(artifactsPar)
                    .flatMap(_.traverse[OrFileErrors, File](_.validationNel).fold(
                      es => Task.fail(new Exception(s"Failed to fetch files: ${es.foldMap(e => e.toString + "\n\n")}")),
                      Task.now(_)
                    ))

                  // filter out coursier metadata, we only want the jars
                  // for the `classpath` field of the .plugin file
                  jarFiles <- artifacts.filter(_.name.endsWith(".jar")).pure[Task]
                } yield jarFiles
              )
            )((_, jars) => jars)
          )

        _ <- Task.delay(println("Artifacts fetched. Preparing to write .plugin file..."))

        // the .plugin file requires all dependency jar paths
        // to be relative to the plugins folder
        classPath = fetchedJarFiles.map(p => datasourcePluginsFolder.toPath.relativize(p.toPath)) ++ List(relativeDatasourceJarPath)

        cpJson = Json.arr(classPath.map(_.toString).map(Json.fromString(_)) :_*)
        mainJar = Json.fromString(relativeDatasourceJarPath)

        // include the datasource jar and classpath into the .plugin file
        outJson = Json.obj("mainJar" -> mainJar, "classPath" -> cpJson).spaces2

        // delete an old .plugin file, write the new one
        _ <- Task.delay {
          Files.deleteIfExists(pluginFilePath)
          Files.write(pluginFilePath, outJson.getBytes, CREATE_NEW)
        }

        _ <- Task.delay(println(".plugin file written. Zipping up tarball..."))

        // equivalent to `ls $datasourcePluginsFolder`, the files and
        // folders we need to zip up to make a valid
        // `plugins` folder
        files = datasourcePluginsFolder.listFiles.map(p => datasourcePluginsFolder.toPath.relativize(p.toPath)).mkString(" ")

        // the `plugins` tarball's location
        tarPath = new File(buildOutputFolder, s"quasar-s3-$thisVersion-q$quasarVersion-explode.tar.gz")

        // the command we run to finish up: zip up (-c) all of
        // the files in our plugins folder ($files), with the
        // plugins folder as "root" of the tarball (-C) and
        // put the tarball into the artifacts folder.
        cmd = s"tar -czvf $tarPath -C $datasourcePluginsFolder/ $files"

        // do it.
        _ <- Task.delay(Runtime.getRuntime().exec(cmd))

        _ <- Task.delay(println(s"Tarball written to ${tarPath}."))
      } yield ()).unsafePerformSync

    }
}
