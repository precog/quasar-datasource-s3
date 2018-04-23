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

  def moduleIdToDependency(moduleId: ModuleID): Dependency =
    Dependency(Module(moduleId.organization, moduleId.name + "_2.12"), moduleId.revision)

  val setAssemblyKey =
    assembleLWC in Compile := {
      val packagedJarFile = (sbt.Keys.`package` in Compile).value
      val buildOutputFolder = (crossTarget in Compile).value
      val lwcPiecesFolder = new File(buildOutputFolder, "lwcpieces")
      val lwcJarFolder = new File(lwcPiecesFolder, "lwc")
      val newLWCJarFile = new File(lwcJarFolder, packagedJarFile.name)
      val pluginJarPath = lwcPiecesFolder.toPath.relativize(newLWCJarFile.toPath).toString
      val pluginFilePath = new File(lwcPiecesFolder, "s3.plugin").toPath
      val resolution =
        Resolution(Dependencies.lwcCore.map(moduleIdToDependency).toSet)
      val cache = Cache.fetch(lwcPiecesFolder, CachePolicy.Update, Cache.defaultChecksums, None, Cache.defaultPool, Cache.defaultTtl)
      val ParallelTask = Tag.of[Parallel]
      (for {
        _                <- Task.delay {
          lwcPiecesFolder.mkdir()
          lwcJarFolder.mkdir()
        }
        resolvedMetadata <-
          ParallelTask.unwrap(
            Applicative[ParallelTask].apply2(
              ParallelTask(Task(Files.copy(packagedJarFile.toPath(), newLWCJarFile.toPath(), REPLACE_EXISTING))),
                ParallelTask(resolution.process.run(Fetch.from(
                  Seq(MavenRepository("https://repo1.maven.org/maven2")),
                cache)))
            )((_, res) => res)
          )
        fetchedArtifacts   <-
          resolvedMetadata.artifacts.toList
            .traverse(
              Cache.file(_, lwcPiecesFolder, CachePolicy.Update).run
            )

        fetchedJarFiles <-
          fetchedArtifacts
            .traverse[({type l[A] = FileError ValidationNel A })#l, File](_.validationNel)
            .fold(
              es => Task.fail(new Exception(s"Failed to fetch files: ${es.foldMap(e => e.toString + "\n\n")}")),
              _.filter(_.name.endsWith(".jar")).pure[Task])

        classPath = fetchedJarFiles.map(p => lwcPiecesFolder.toPath.relativize(p.toPath))

        cpJson = classPath.map(s => "\"" + s + "\"").mkString("[\n    ", ",\n    ", "\n  ]")
        outJson = s"""{\n  "main_jar": "$pluginJarPath",\n  "classpath": $cpJson\n}"""

        _ <- Task.delay {
          Files.deleteIfExists(pluginFilePath)
          Files.write(pluginFilePath, outJson.getBytes, CREATE_NEW)
        }

        files = lwcPiecesFolder.listFiles.map(p => lwcPiecesFolder.toPath.relativize(p.toPath)).mkString(" ")
        tarPath = new File(buildOutputFolder, "lwc.tar.gz")

        cmd = s"tar -czvf $tarPath -C $lwcPiecesFolder/ $files"

        _ <- Task.delay(Runtime.getRuntime().exec(cmd))
      } yield ()).unsafePerformSync



    }
}