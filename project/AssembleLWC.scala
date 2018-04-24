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

import sbt.{Cache => _, MavenRepository => _, Tags => _, Task => _, _}
import sbt.Keys._
import scalaz._, Scalaz._
import Tags.Parallel
import scalaz.concurrent.Task
import Task._

object AssembleLWC {
  val assembleLWC = TaskKey[Unit]("assembleLWC")

  // SBT needs `ModuleId` to declare dependencies with
  // `libraryDependencies`, and coursier wants `Dependency`.
  // Converting is straightforward but requires knowing the
  // `scalaVersion`; I've hard-coded it to 2.12 here.
  def moduleIdToDependency(moduleId: ModuleID): Dependency =
    Dependency(Module(moduleId.organization, moduleId.name + "_2.12"), moduleId.revision)

  val setAssemblyKey =
    assembleLWC in Compile := {
      // the location of the LWC jar itself. we make sure
      // it's been built by calling `package` here.
      val packagedJarFile = (sbt.Keys.`package` in Compile).value

      // where all of the artifacts for the project are.
      // notably the LWC jar, and later on the assembled
      // LWC tarball.
      val buildOutputFolder = (crossTarget in Compile).value

      // we assemble every component of the final tarball
      // in this folder, to be exploded over the user's
      // `plugins` folder.
      val lwcPluginsFolder = new File(buildOutputFolder, "plugins")

      // a folder for just the LWC jar itself, shared by
      // other LWC's in the same `plugins` folder.
      val lwcJarFolder = new File(lwcPluginsFolder, "lwc")

      // the jar file's own path.
      val lwcJarFile = new File(lwcJarFolder, packagedJarFile.name)

      // the LWC jar's path relative to lwcPluginsFolder;
      // included in the generated .plugin file to let quasar
      // know where to load it from.
      val relativeLWCJarPath =
        lwcPluginsFolder.toPath.relativize(lwcJarFile.toPath).toString

      // the path to the generated .plugin file.
      val pluginFilePath = new File(lwcPluginsFolder, "s3.plugin").toPath

      // start coursier on resolving all of the LWC's
      // dependencies, *except* for quasar. quasar and its
      // dependencies are already present in the user's
      // `plugins` folder.
      val resolution =
        Resolution(Dependencies.lwcCore.map(moduleIdToDependency).toSet)

      // we're using lwcPluginsFolder as a coursier cache while fetching
      // our dependencies, because that's the format of a `plugins` folder.
      val cache = Cache.fetch(
        lwcPluginsFolder,
        CachePolicy.Update)

      // I don't want to add kind-projector to  the compiler
      // plugins, so I'm hard-coding this type alias.
      // later on we're going to make a `List[FileError \/ A]`
      // into a `Validation[NonEmptyList[FileError], List[A]]`
      type OrFileErrors[A] = FileError ValidationNel A

      (for {
        // make the output plugins folder and the folder inside
        // which houses the lwc jar.
        _ <- Task.delay {
          lwcPluginsFolder.mkdir()
          lwcJarFolder.mkdir()
        }

        _ <- Task.delay(println("Fetching artifacts with coursier..."))

        // coursier prefers that we fetch metadata before fetching
        // artifacts. we do that in parallel with copying the lwc
        // jar to its new place, because they don't depend on one
        // another.
        fetchedJarFiles <-
          Parallel.unwrap(
            Applicative[ParallelTask].apply2(
              Parallel(
                Task(Files.copy(packagedJarFile.toPath(), lwcJarFile.toPath(), REPLACE_EXISTING))
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
                      Parallel(Cache.file(f, lwcPluginsFolder, CachePolicy.Update).run)
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
        classPath = fetchedJarFiles.map(p => lwcPluginsFolder.toPath.relativize(p.toPath))

        // format the classPath as readable json for the .plugin file
        cpJson = classPath.map(s => "\"" + s + "\"").mkString("[\n    ", ",\n    ", "\n  ]")

        // include the LWC jar and classpath into the .plugin file
        outJson = s"""{\n  "main_jar": "$relativeLWCJarPath",\n  "classpath": $cpJson\n}"""

        // delete an old .plugin file, write the new one
        _ <- Task.delay {
          Files.deleteIfExists(pluginFilePath)
          Files.write(pluginFilePath, outJson.getBytes, CREATE_NEW)
        }

        _ <- Task.delay(println(".plugin file written. Zipping up tarball..."))

        // equivalent to `ls $lwcPluginsFolder`, the files and
        // folders we need to zip up to make a valid
        // `plugins` folder
        files = lwcPluginsFolder.listFiles.map(p => lwcPluginsFolder.toPath.relativize(p.toPath)).mkString(" ")

        // the `plugins` tarball's location
        tarPath = new File(buildOutputFolder, "lwc.tar.gz")

        // the command we run to finish up: zip up (-c) all of
        // the files in our plugins folder ($files), with the
        // plugins folder as "root" of the tarball (-C) and
        // put the tarball into the artifacts folder.
        cmd = s"tar -czvf $tarPath -C $lwcPluginsFolder/ $files"

        // do it.
        _ <- Task.delay(Runtime.getRuntime().exec(cmd))

        _ <- Task.delay(println(s"Tarball written to ${tarPath}."))
      } yield ()).unsafePerformSync

    }
}