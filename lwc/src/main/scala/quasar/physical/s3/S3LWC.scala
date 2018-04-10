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

import scalaz._
import scalaz.concurrent.Task

object S3LWC extends LightweightConnector {
  private def delayEither[E, A](a: E \/ A): EitherT[Task, E, A] = {
    EitherT.eitherT(Task.delay(a))
  }

  def init(uri: ConnectionUri): EitherT[Task, String, (LightweightFileSystem, Task[Unit])] =
    EitherT.rightT(Task.delay {
      val client = PooledHttp1Client()
      (new S3FS(uri, client), client.shutdown)
    })

}

final class S3FS(uri: ConnectionUri, client: Client) extends LightweightFileSystem {
  def children(dir: ADir): Task[Option[Set[PathSegment]]] = ???

  def exists(file: AFile): Task[Boolean] = ???

  def read(file: AFile): Task[Option[Stream[Task, Data]]] = ???
}