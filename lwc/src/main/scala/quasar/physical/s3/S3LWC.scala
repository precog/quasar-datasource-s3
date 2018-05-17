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

import quasar.fs.FileSystemType
import quasar.fs.mount.ConnectionUri
import quasar.mimir.{LightweightConnector, LightweightFileSystem, SlamEngine}
import slamdata.Predef._

import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.Uri

import scalaz._
import scalaz.syntax.either._
import scalaz.concurrent.Task

sealed trait S3JsonParsing

// Two ways to parse JSON that we offer: as an array of rows,
// or as rows delimited by newlines.
object S3JsonParsing {
  case object JsonArray extends S3JsonParsing
  case object LineDelimited extends S3JsonParsing
}

final class S3LWC(jsonParsing: S3JsonParsing) extends LightweightConnector {
  // To set up a new S3 filesystem we validate the
  // `ConnectionUri` as a real URI, and create an http
  // client to use to contact S3.
  def init(uri: ConnectionUri): EitherT[Task, String, (LightweightFileSystem, Task[Unit])] =
    EitherT(Task.suspend {
      val httpUri = Uri.fromString(uri.value)
      httpUri.fold(
        e => Task.fail(new Exception(s"Error when parsing $httpUri as URI:\n$e")),
        { u =>
          val client = PooledHttp1Client()
          Task.now((new S3LWFS(jsonParsing, u, client), client.shutdown))
        }
      ).map(_.right[String])
    })
}

// Objects extending SlamEngine are the interface to the
// lightweight connector system. We have two, one for
// array-based JSON, one for line-delimited JSON.
object S3JsonArray extends SlamEngine {
  // FileSystemType("s3array") means that to add a mount under
  // this connector, they can set the mount key (in the JSON
  // sent) to "s3array"
  val Type: FileSystemType = FileSystemType("s3array")
  // instantiate the LWC, with array-based JSON parsing.
  val lwc: LightweightConnector = new S3LWC(S3JsonParsing.JsonArray)
}

// The exact same as the above, but with line-delimited JSON
// parsing.
object S3LineDelimited extends SlamEngine {
  val Type: FileSystemType = FileSystemType("s3linedelim")
  val lwc: LightweightConnector = new S3LWC(S3JsonParsing.LineDelimited)
}
