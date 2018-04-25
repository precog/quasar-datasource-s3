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
import org.http4s.{Method, Request, Status, Uri}
import pathy.Path
import quasar.contrib.pathy._
import scalaz.concurrent.Task

// the simplest method to implement, just need to check that
// HEAD doesn't give a 404.
object exists {
  def apply(client: Client, uri: Uri, file: AFile): Task[Boolean] = {
    // print pathy.Path as POSIX path, without leading slash
    val objectPath = Path.posixCodec.printPath(file).drop(1)

    // just request to the object's path on the bucket
    val queryUri = uri / objectPath

    // with HEAD, to get metadata
    val request = Request(uri = queryUri, method = Method.HEAD)

    client.status(request).flatMap {
      // self-explanatory.
      case Status.Ok => Task.now(true)
      case Status.NotFound => Task.now(false)
      case s => Task.fail(new Exception(s"Unexpected status returned during `exists` call: $s"))
    }
  }
}