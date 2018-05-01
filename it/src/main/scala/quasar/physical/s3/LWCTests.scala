/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.s3

import slamdata.Predef._

import pathy._
import quasar.Data
import quasar.contrib.pathy._
import quasar.fs.mount.ConnectionUri
import quasar.mimir.LightweightFileSystem
import scalaz._, Scalaz._
import scalaz.concurrent.Task

final case class TestFS(
  uri: ConnectionUri,
  // map from file paths to JSON data;
  // if the file exists but is not parseable
  // it's `None`
  allFiles: Map[AFile, Option[List[Data]]],
  // full directory structure
  allPaths: Map[ADir, List[AFile \/ ADir]]
)

final case class MultiCauseException(val msg: Option[String], val causes: List[Throwable]) extends Exception (
)

final class LWCTests {

  type T = Task[Unit]

  def main(args: Array[String]) = {
    (for {
      arrLwfsE <- S3JsonArray.lwc.init(testUri).run
      \/-((arrLwfs, _)) = arrLwfsE
      lineLwfsE <- S3LineDelimited.lwc.init(testUri).run
      \/-((lineLwfs, _)) = lineLwfsE
      arrR = testLaws(arrLwfs, arrData)
      lineR = testLaws(lineLwfs, lineData)
      _ <- gather(List(arrR, lineR))
    } yield ()).unsafePerformSync
  }

  def testDir: ADir = Path.rootDir </> Path.dir("testData")
  def testUri: ConnectionUri = ConnectionUri("http://slamdata-test-public")
  def testArrayObject: AFile = testDir </> Path.file("array.json")
  def testLineObject: AFile = testDir </> Path.file("lines.json")

  val lineData = TestFS(
    testUri,
    Map(
      testArrayObject -> Some(List(Data.Arr(List(Data.Arr(List(Data.Str("array"))))))),
      testLineObject -> Some(List(Data.Arr(List(Data.Str("lines")))))
    ),
    Map(
      Path.rootDir -> List(testDir.right[AFile]),
      testDir -> List(testArrayObject.left[ADir], testLineObject.left[ADir])
    )
  )

  val arrData = TestFS(
    testUri,
    Map(
      testArrayObject -> Some(List(Data.Arr(List(Data.Str("array"))))),
      testLineObject -> None
    ),
    Map(
      Path.rootDir -> List(testDir.right[AFile]),
      testDir -> List(testArrayObject.left[ADir], testLineObject.left[ADir])
    )
  )

  def traverseL[F[_]: Traverse, G[_]: Applicative, A, B]
    (as: F[A])
    (f: A => G[B])
  : G[F[(A, B)]] =
    as.traverse(a =>
      f(a).strengthL(a)
    )

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def showThrowable(ex: Throwable): String = {
    def flatten(th: Throwable): List[Throwable] \/ Throwable = th match {
      case MultiCauseException(Some(msg), causes) =>
        \/-(MultiCauseException(Some(msg), causes.flatMap { c =>
          flatten(c) match {
            case -\/(newCauses) =>
              newCauses
            case \/-(n) =>
              n :: Nil
          }
        }))
      case MultiCauseException(None, causes) =>
        -\/(causes)
      case c =>
        \/-(c)
    }
    val newEx = flatten(ex) match {
      case -\/(causes) =>
        MultiCauseException(None, causes)
      case \/-(th) =>
        th
    }

    import java.io.StringWriter
    import java.io.PrintWriter

    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    newEx.printStackTrace(pw)
    (newEx match {
      case MultiCauseException(msg, causes) =>
        s"""
        |Errors occurred while testing:
        |${causes.map(showThrowable).intercalate("\n")}
        """.stripMargin.trim
      case _ =>
        ""
    }) + sw.toString
  }

  def gather[F[_]: Traverse, A](tasks: F[Task[A]]): Task[F[A]] = {
    val attempted: Task[F[Throwable \/ A]] = tasks.traverse(_.attempt)
    val validated: Task[Throwable ValidationNel F[A]] = attempted.map(_.traverse(_.validationNel[Throwable]))
    validated.flatMap {
      case Failure(es) =>
        Task.fail(MultiCauseException(None, es.list.toList))
      case Success(fa) =>
        Task.now(fa)
    }
  }

  def testLaws(lwfs: LightweightFileSystem, fs: TestFS): T = for {
    _ <- existTests(lwfs, fs)
    _ <- Task.now(())
  } yield ()

  def existTests(lwfs: LightweightFileSystem, fs: TestFS): T = fs match {
    case TestFS(_, files, paths) =>
      val allFiles: List[AFile] =
        paths
          .values
          .flatMap(x => x)
          .flatMap {
            case -\/(f) =>
              f :: Nil
            case _ =>
              Nil
          }
          .toList
      mustExist(allFiles, lwfs)
  }

  def mustExist(paths: List[AFile], lwfs: LightweightFileSystem): T = for {
    exists <- traverseL(paths)(lwfs.exists)
    _ <- gather(exists.map {
      case (file, doesExist) =>
        if (!doesExist)
          Task.fail(new Exception(
            "File $file does not exist despite being listed in the children of $path"
          ))
        else Task.now(())
    })
  } yield ()

  def existsChildrenLaw(path: ADir, lwfs: LightweightFileSystem): T = for {
    childrenErr <- lwfs.children(path)
    children <- childrenErr
      .cata(Task.now, Task.fail(new Exception("Folder does not exist")))
    files = children.toList.collect {
      case \/-(Path.FileName(file)) => path </> Path.file(file)
    }
    _ <- mustExist(files, lwfs)
  } yield ()

  def reconstructChildren(path: ADir, lwfs: LightweightFileSystem): Task[List[ADir \/ AFile]] = for {
    childrenErr <- lwfs.children(path)
    children <- childrenErr
      .cata(Task.now, Task.fail(new Exception("Folder does not exist")))
    reconstructed = children.toList.map(_.bimap({
      case Path.DirName(dir) => path </> Path.dir(dir)
    }, {
      case Path.FileName(file) => path </> Path.file(file)
    }))
  } yield reconstructed

  def readChildrenLaw(path: ADir, lwfs: LightweightFileSystem): T = for {
    children <- reconstructChildren(path, lwfs)
    files = children.collect {
      case \/-(file) => file
    }
    reads <- traverseL(files)(lwfs.read)
    _ <- reads.traverse {
      case (file, contentStream) =>
        if (contentStream.isEmpty)
          Validation.failureNel(new Exception(
            "File $file is not readable despite being listed in the children of $path"
          ))
        else
          Validation.success(())
    }.fold(es => Task.fail(new Exception("""Errors: ${es.mkString("\n")}""")),
           _ => Task.now(()))
  } yield ()

}