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

import sbt.{MavenRepository => _, Cache => _, _}
import sbt.Keys._
import scalaz._, Scalaz._

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
      lwcPiecesFolder.mkdir()
      lwcJarFolder.mkdir()
      val resolution =
        Resolution(Dependencies.lwcCore.map(moduleIdToDependency).toSet)
      val cache = Cache.fetch(lwcPiecesFolder, CachePolicy.Update, Cache.defaultChecksums, None, Cache.defaultPool, Cache.defaultTtl)
      (for {
        fetched <- resolution.process.run(Fetch.from(
          Seq(MavenRepository("https://repo1.maven.org/maven2")),
          cache))
        artifacts <- fetched.artifacts.toList.traverse(Cache.file(_, lwcPiecesFolder, CachePolicy.Update, Cache.defaultChecksums, None, Cache.defaultPool, Cache.defaultTtl).run)
      } yield artifacts.sequence[({type l[A] = FileError \/ A })#l, File]).unsafePerformSync
      val newLWCJarFile = new File(lwcJarFolder, packagedJarFile.name)
      Files.copy(packagedJarFile.toPath(), newLWCJarFile.toPath(), REPLACE_EXISTING)
      // println(s"mv $packaged $path/")
      val pluginJarPath = lwcPiecesFolder.toPath.relativize(newLWCJarFile.toPath).toString
      val pluginFilePath = new File(lwcPiecesFolder, "s3.plugin").toPath
      // todo: replace with the results from `Cache.fetch`
      val classPath = Files.walk(new File(lwcPiecesFolder, "https").toPath)
        .map[File](_.toFile)
        .filter { p => p.isFile && p.name.endsWith(".jar") }
        .map[Path](p => lwcPiecesFolder.toPath.relativize(p.toPath))
        .collect(Collectors.toList[Path])
        .asScala

      val cpJson = classPath.map(s => "\"" + s + "\"").mkString("[\n    ", ",\n    ", "\n  ]")
      val mj = s"${"\""}$pluginJarPath${"\""}"

      val outJson = s"""{\n  "main_jar": $mj,\n  "classpath": $cpJson\n}"""

      Files.deleteIfExists(pluginFilePath)
      Files.write(pluginFilePath, outJson.getBytes, CREATE_NEW, TRUNCATE_EXISTING)

      val files = lwcPiecesFolder.listFiles.map(p => lwcPiecesFolder.toPath.relativize(p.toPath).toString).mkString(" ")
      val tarPath = new File(buildOutputFolder, "lwc.tar.gz")

      val cmd = s"tar -czvf $tarPath -C $lwcPiecesFolder/ $files"

      Runtime.getRuntime().exec(cmd)
    }
}