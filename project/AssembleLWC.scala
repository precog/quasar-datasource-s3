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

object AssembleLWC {
  val assembleLWC = TaskKey[Unit]("assembleLWC")

  val setAssemblyKey =
    assembleLWC in Compile := {
      val packaged = (sbt.Keys.`package` in Compile).value
      val outPath = (crossTarget in Compile).value
      val path = new File(outPath, "assembleLWC")
      val lwcJarFolder = new File(path, "lwc")
      path.mkdir()
      lwcJarFolder.mkdir()
      val start =
        Resolution(
          Dependencies.lwcCore.map(
            moduleId => Dependency(Module(moduleId.organization, moduleId.name + "_2.12"), moduleId.revision)
          ).toSet
        )
      val cache = Cache.fetch(path, CachePolicy.Update, Cache.defaultChecksums, None, Cache.defaultPool, Cache.defaultTtl)
      val fetch = start.process.run(Fetch.from(
        Seq(MavenRepository("https://repo1.maven.org/maven2")),
        cache
      )).unsafePerformSync
      fetch.artifacts
        .map(Cache.file(_, path, CachePolicy.Update, Cache.defaultChecksums, None, Cache.defaultPool, Cache.defaultTtl).run.unsafePerformSync)
      val newLWCJarFile = new File(lwcJarFolder, packaged.name)
      if (packaged.exists()) {
        Files.move(packaged.toPath(), newLWCJarFile.toPath(), REPLACE_EXISTING)
      }
      // println(s"mv $packaged $path/")
      val pluginJarPath = path.toPath.relativize(newLWCJarFile.toPath).toString
      val pluginFilePath = new File(path, "s3.plugin").toPath
      // todo: replace with the results from `Cache.fetch`
      val classPath = Files.walk(new File(path, "https").toPath)
        .map[File](_.toFile)
        .filter { p => p.isFile && p.name.endsWith(".jar") }
        .map[Path](p => path.toPath.relativize(p.toPath))
        .collect(Collectors.toList[Path])
        .asScala

      val cpJson = classPath.map(s => "\"" + s + "\"").mkString("[\n    ", ",\n    ", "\n  ]")
      val mj = s"${"\""}$pluginJarPath${"\""}"

      val outJson = s"""{\n  "main_jar": $mj,\n  "classpath": $cpJson\n}"""

      Files.deleteIfExists(pluginFilePath)
      Files.write(pluginFilePath, outJson.getBytes, CREATE_NEW, TRUNCATE_EXISTING)

      val files = path.listFiles.map(p => path.toPath.relativize(p.toPath).toString).mkString(" ")
      val tarPath = new File(outPath, "lwc.tar.gz")

      val cmd = s"tar -czvf $tarPath -C $path/ $files"

      Runtime.getRuntime().exec(cmd)
    }
}