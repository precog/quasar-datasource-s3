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
import quasar.fs.mount.ConnectionUri
import quasar.mimir.{LightweightConnector, LightweightFileSystem}
import slamdata.Predef._

import fs2.Stream
import org.http4s.client._
import org.http4s.client.blaze._
import org.http4s.{Method, Request, Status, Uri}
import pathy.Path

import scalaz._
import scalaz.syntax.applicative._
import scalaz.syntax.std.boolean._
import scalaz.concurrent.Task

object S3LWC extends LightweightConnector {
  private def delayEither[E, A](a: E \/ A): EitherT[Task, E, A] = {
    EitherT.eitherT(Task.delay(a))
  }

  def init(uri: ConnectionUri): EitherT[Task, String, (LightweightFileSystem, Task[Unit])] =
    EitherT.rightT(Task.delay {
      val client = PooledHttp1Client()
      val \/-(httpUri) = Uri.fromString(uri.value)
      (new S3FS(httpUri, client), client.shutdown)
    })

}

final class S3FS(uri: Uri, client: Client) extends LightweightFileSystem {

  implicit final class optApplyOps[B](self: B) {
    def >+>[A](opt: Option[A], f: (B, A) => B): B = opt.fold(self)(f(self, _))
  }

  private def aPathToObjectPrefix(apath: APath): Option[String] = {
    val pathPrinted = Path.posixCodec.printPath(apath)
    // don't provide prefix if listing the top-level,
    // otherwise drop the first /
    (pathPrinted != Path.rootDir).option(pathPrinted.drop(1).replace("/", "%2F"))
  }

  def children(dir: ADir): Task[Option[Set[PathSegment]]] = {
    val objectPrefix = aPathToObjectPrefix(dir)
    val maxKeys = 100
    val queryUri = ((uri / "") +?
      ("max-keys", 1) +?
      ("list-type", 2)) >+>[String]
      (objectPrefix, _ +? ("prefix", _))
    println(s"uri: $queryUri")
    Task.suspend {
      println(client.expect[String](queryUri).unsafePerformSync)
      ???
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
        case s => Task.fail(new Exception("Unexpected status $s"))
      }
    }
  }

  def read(file: AFile): Task[Option[Stream[Task, Data]]] = ???
}