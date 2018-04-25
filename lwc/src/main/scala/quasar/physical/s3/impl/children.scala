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
      // parse S3 object paths as POSIX paths with a missing
      // slash at the beginning. relative paths are disallowed
      // by the `_ => none` cases
      Path.posixCodec.parsePath(
        _ => none,
        _.some,
        _ => none,
        _.some
      )("/" + name)
    // unsafeSandboxAbs is from quasar.
    // posixCodec parses unsandboxed ("real") paths
    unsandboxed.map(unsafeSandboxAbs)
  }

  // I'm sick of writing `opt.fold(a)(f(a, _)). you can
  // interpret this as converting an `Option[A]` to a
  // `B => B` using a "combiner" function (B, A) => B
  implicit private final class optApplyOps[B](self: B) {
    def >+>[A](opt: Option[A], f: (B, A) => B): B = opt.fold(self)(f(self, _))
  }

  // S3 provides a recursive listing (akin to `find` or
  // `dirtree`); we filter out children that aren't direct
  // children. we can only list 1000 keys, and need pagination
  // to do more. that's 1000 *recursively listed* keys, so we
  // could conceivably list *none* of the direct children of a
  // folder without pagination, depending on the order AWS
  // sends them in.
  def apply(client: Client, uri: Uri, dir: ADir): Task[Option[Set[PathSegment]]] = {
    // self-explanatory, converts a pathy Path to an S3
    // object prefix.
    val objectPrefix = aPathToObjectPrefix(dir)

    // Start with the bucket URI; add an extra `/` on the end
    // so that S3 understands us.
    // `list-type=2` asks for the new version of the list api.
    // We only add the `objectPrefix` if it's not empty;
    // the S3 API doesn't understand empty `prefix`.
    val queryUri = ((uri / "") +?
      ("list-type", 2)) >+>[String]
      (objectPrefix, _ +? ("prefix", _))

      for {
        // request to S3, parse the response as XML.
        topLevelElem <- client.expect[xml.Elem](queryUri)
        // grab the child names from the response
        children <- for {
          contents <- Task.suspend {
            // grab <Contents>
            try {
              Task.now(topLevelElem \\ "Contents")
            } catch {
              case ex: Exception =>
                Task.fail(new Exception("XML received from AWS API has no top-level <Contents> element", ex))
            }
          }
          // grab all of the <Key> elements from <Contents>
          names <- contents.toList.traverse { elem =>
            try {
              Task.now((elem \\ "Key").text)
            } catch {
              case ex: Exception =>
                Task.fail(new Exception("XML received from AWS API has no <Key> elements under <Contents>", ex))
            }
          }
        } yield names
        // convert S3 object names to paths
        childPaths <- children.traverse(s3NameToPath)
          .cata(Task.now, Task.fail(new Exception(s"Failed to parse object path in S3 API response")))
        // deal with some S3 idiosyncrasies.
        // TODO: Pagination
        result =
        if (dir =/= Path.rootDir && !childPaths.element(dir)) None
        else Some(
          childPaths
            .filter(path =>
              // AWS includes the folder itself in the returned
              // results, so we have to remove it.
              // same goes for files in folders *below* the listed folder.
              path =/= dir && Path.parentDir(path) === Some(dir))
            // take the file name or folder name of the
            // child out of the full object path
            // TODO: report an error when `Path.peel` fails, that's nonsense
            .flatMap(Path.peel(_).toList)
            // remove duplicates
            // TODO: report an error if there are duplicates
            .map(_._2).toSet)
      } yield result
  }

}