/*
 * Copyright 2020 Precog Data
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
package impl

import slamdata.Predef._
import quasar.api.resource.ResourcePath
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.contrib.pathy._

import cats.Monad
import cats.effect.Resource
import cats.syntax.applicative._

import fs2.Stream
import org.http4s.{Request, Status, Uri}
import org.http4s.client._
import pathy.Path
import shims._

object evaluate {

  def apply[F[_]: Monad: MonadResourceErr](
      client: Client[F], uri: Uri, file: AFile)
      : Resource[F, Stream[F, Byte]] = {
    // Convert the pathy Path to a POSIX path, dropping
    // the first slash, which is what S3 expects for object paths
    val objectPath = Path.posixCodec.printPath(file).drop(1)
    // Put the object path after the bucket URI
    val queryUri = appendPathS3Encoded(uri, objectPath)
    val request = Request[F](uri = queryUri)

    streamRequest[F](client, request, file)
  }

  ////

  private def streamRequest[F[_]: Monad: MonadResourceErr](
      client: Client[F], req: Request[F], file: AFile)
      : Resource[F, Stream[F, Byte]] =
    client.run(req).evalMap[F, Stream[F, Byte]](res => res.status match {
      case Status.NotFound =>
        MonadResourceErr[F].raiseError(ResourceError.pathNotFound(ResourcePath.leaf(file)))

      case Status.Forbidden =>
        MonadResourceErr[F].raiseError(accessDeniedError(ResourcePath.leaf(file)))

      case Status.Ok =>
        res.body.pure[F]

      case other =>
        MonadResourceErr[F].raiseError(unexpectedStatusError(
          ResourcePath.leaf(file),
          other))
    })
}
