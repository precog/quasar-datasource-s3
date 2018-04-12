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

import quasar.Data
import quasar.contrib.pathy._
import quasar.mimir.LightweightFileSystem
import slamdata.Predef._

import fs2.Stream
import org.http4s.client._
import org.http4s.scalaxml.{xml => xmlDecoder}
import org.http4s.{Method, Request, Status, Uri}
import pathy.Path
import scala.xml

import scalaz.Scalaz._
import scalaz.concurrent.Task

final class S3LWFS(uri: Uri, client: Client) extends LightweightFileSystem {

  implicit private final class optApplyOps[B](self: B) {
    def >+>[A](opt: Option[A], f: (B, A) => B): B = opt.fold(self)(f(self, _))
  }

  private type APath = Path[Path.Abs, Any, Path.Sandboxed]

  private def aPathToObjectPrefix(apath: APath): Option[String] = {
    // don't provide prefix if listing the top-level,
    // otherwise drop the first /
    (apath != Path.rootDir).option {
      Path.posixCodec.printPath(apath).drop(1) // .replace("/", "%2F")
    }
  }

  private def s3NameToPath(name: String): Option[APath] = {
    val unsandboxed =
      Path.posixCodec.parsePath[Option[Path[Path.Abs, Any, Path.Unsandboxed]]](
        _ => none,
        _.some,
        _ => none,
        _.some
      )("/" + name)
    unsandboxed.map(unsafeSandboxAbs)
  }

  // this is a recursive listing which filters out children that aren't direct children.
  // it also will only list 100 keys (and 1000 maximum, and it needs pagination to do more)
  // that's 100 *recursively listed* keys, which means we could conceivably list *none*
  // of the direct children of a folder, depending on the order AWS sends them in.
  def children(dir: ADir): Task[Option[Set[PathSegment]]] = {
    val objectPrefix = aPathToObjectPrefix(dir)
    val maxKeys = 100
    val queryUri = ((uri / "") +?
      ("list-type", 2)) >+>[String]
      (objectPrefix, _ +? ("prefix", _))
    Task.suspend {
      for {
        // TODO: better error handling, please
        topLevelElem <- client.expect[xml.Elem](queryUri)
        children <- Task.delay {
          // TODO: here too, these xml ops can all fail
          (topLevelElem \\ "Contents").map(e => (e \\ "Key").text).toList
        }
        childPaths <- children.traverse(s3NameToPath).fold[Task[List[APath]]](Task.fail(new Exception("Failed to parse S3 API response")))(Task.now)
        result =
        if (!childPaths.element(dir)) None
        else Some(
          childPaths.filter(path =>
            // AWS includes the folder itself in the returned results if it exists, so we have to remove it.
            // same goes for files in folders *below* the listed folder.
            path =/= dir && Path.parentDir(path) === Some(dir))
          .flatMap(Path.peel(_).toList)
          .map(_._2).toSet)
      } yield result
    }
  }

  def exists(file: AFile): Task[Boolean] = {
    val objectPath = Path.posixCodec.printPath(file).drop(1)
    Task.suspend {
      val queryUri = uri / objectPath
      val request = Request(uri = queryUri, method = Method.HEAD)
      println(queryUri)
      client.status(request).flatMap {
        case Status.Ok => true.point[Task]
        case Status.NotFound => false.point[Task]
        case s => Task.fail(new Exception(s"Unexpected status $s"))
      }
    }
  }

  private def streamingParse(stream: scalaz.stream.Process[Task, String]): Stream[Task, Data] = {
    Stream.empty
  }

  def read(file: AFile): Task[Option[Stream[Task, Data]]] = {
    val objectPath = Path.posixCodec.printPath(file).drop(1)
    val queryUri = uri / objectPath
    // client.streaming(queryUri)(resp => resp.body)
    ???
  }
}