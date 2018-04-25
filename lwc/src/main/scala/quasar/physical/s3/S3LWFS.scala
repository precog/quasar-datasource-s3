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
import org.http4s.client.Client
import org.http4s.Uri
import scalaz.concurrent.Task

// three functions comprise the LightweightFileSystem API
// (and informally, the lightweight connector API itself)
// since they're all implemented in their own files and
// delegated to, I'll focus on the interface itself.
final class S3LWFS(jsonParsing: S3JsonParsing, uri: Uri, client: Client) extends LightweightFileSystem {

  // analogue of POSIX "ls" or Windows "dir", used to find
  // the "immediate children" of a directory.
  // on S3, object names roughly follow the standard
  // folder1/folder2/file path format, which makes this
  // function's implementation fairly natural.
  def children(dir: ADir): Task[Option[Set[PathSegment]]] = impl.children(client, uri, dir)

  // reads from an S3 object containing JSON, which is parsed
  // according to `jsonParsing`. streams results back.
  def read(file: AFile): Task[Option[Stream[Task, Data]]] = impl.read(jsonParsing, client, uri, file)

  // does exactly what you'd guess, checks if a file (object)
  // exists. the only non-obvious part is that it only works
  // for files and not folders, but `children` can be used to
  // check if a folder exists.
  def exists(file: AFile): Task[Boolean] = impl.exists(client, uri, file)

}