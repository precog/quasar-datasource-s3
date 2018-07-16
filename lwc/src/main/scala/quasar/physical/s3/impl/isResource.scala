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
import org.http4s.{Method, Request, Status, Uri, Headers}
import org.http4s.headers.Range
import pathy.Path
import quasar.contrib.pathy._
import cats.effect.{Effect, Timer}
import cats.syntax.flatMap._
import cats.syntax.applicative._

// The simplest method to implement, check that HEAD doesn't
// give a 404.
object isResource {
  def apply[F[_]: Effect: Timer](client: Client[F], uri: Uri, file: AFile, sign: Request[F] => F[Request[F]]): F[Boolean] = {
    // Print pathy.Path as POSIX path, without leading slash,
    // for S3's consumption.
    val objectPath = Path.posixCodec.printPath(file).drop(1)

    // Add the object's path to the bucket URI.
    val queryUri = uri / objectPath

    // Request with HEAD, to get metadata.
    // attempt to get the first byte to verify this is not empty
    val request = Request[F]()
      .withUri(queryUri)
      .withMethod(Method.HEAD)
      .withHeaders(Headers(Range(0, 1)))

    if (Path.identicalPath(Path.rootDir, file)) {
      false.pure[F]
    } else {
      // Don't use the metadata, just check the request status
      sign(request) >>= (r =>
        client.status(r) >>= {
          case Status.Ok => true.pure[F]
          case Status.PartialContent => true.pure[F]
          case Status.NotFound => false.pure[F]
          case Status.RangeNotSatisfiable => false.pure[F]
          case Status.Forbidden => Effect[F].raiseError(new Exception(s"Permission denied. Make sure you have access to the configured bucket"))
          case s => Effect[F].raiseError(new Exception(s"Unexpected status returned during `isResource` call: $s"))
        })
    }
  }
}
