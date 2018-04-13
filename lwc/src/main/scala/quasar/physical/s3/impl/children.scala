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

package quasar.physical.s3.impl

import slamdata.Predef._

import org.http4s.client.Client
import org.http4s.Uri
import org.http4s.scalaxml.{xml => xmlDecoder}
import pathy.Path
import quasar.contrib.pathy._
import scala.xml
import scalaz.concurrent.Task
import scalaz.std.option._
import scalaz.std.list._
import scalaz.syntax.equal._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._

object children {
  private def aPathToObjectPrefix(apath: APath): Option[String] = {
    // don't provide prefix if listing the top-level,
    // otherwise drop the first /
    (apath != Path.rootDir).option {
      Path.posixCodec.printPath(apath).drop(1) // .replace("/", "%2F")
    }
  }

  private def s3NameToPath(name: String): Option[APath] = {
    val unsandboxed: Option[Path[Path.Abs, Any, Path.Unsandboxed]] =
      Path.posixCodec.parsePath(
        _ => none,
        _.some,
        _ => none,
        _.some
      )("/" + name)
    unsandboxed.map(unsafeSandboxAbs)
  }

  implicit private final class optApplyOps[B](self: B) {
    def >+>[A](opt: Option[A], f: (B, A) => B): B = opt.fold(self)(f(self, _))
  }

  // this is a recursive listing which filters out children that aren't direct children.
  // it also will only list 100 keys (and 1000 maximum, and it needs pagination to do more)
  // that's 100 *recursively listed* keys, which means we could conceivably list *none*
  // of the direct children of a folder, depending on the order AWS sends them in.
  def apply(client: Client, uri: Uri, dir: ADir): Task[Option[Set[PathSegment]]] = {
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
        // TODO: and here too
        childPaths <- children.traverse(s3NameToPath)
          .cata(Task.now, Task.fail(new Exception("Failed to parse S3 API response")))
        // TODO: I want to log it when we have `childPaths.nonEmpty` but `!childPaths.element(dir)`.
        result =
        if (dir =/= Path.rootDir && !childPaths.element(dir)) None
        else Some(
          childPaths
            .filter(path =>
              // AWS includes the folder itself in the returned results if it exists, so we have to remove it.
              // same goes for files in folders *below* the listed folder.
              path =/= dir && Path.parentDir(path) === Some(dir))
            // TODO: report an error when `Path.peel` fails, that's nonsense
            .flatMap(Path.peel(_).toList)
            // TODO: report an error if there are duplicates
            .map(_._2).toSet)
      } yield result
    }
  }

}