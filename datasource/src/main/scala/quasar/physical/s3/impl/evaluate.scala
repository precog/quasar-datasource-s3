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

import cats.{Functor, Monad}
import cats.effect.{ExitCase, Resource, Sync}
import cats.syntax.applicative._
import cats.effect.concurrent.Ref
import cats.implicits._

import fs2.{Stream, Pipe}
import org.http4s.{Header, Headers, RangeUnit, Request, Status, Uri}
import org.http4s.headers.{`Content-Length`, `Content-Range`}
import org.http4s.headers.Range.SubRange
import org.http4s.client._
import pathy.Path
import shims._

object evaluate {

  def apply[F[_]: Sync: MonadResourceErr](
      client: Client[F], uri: Uri, file: AFile)
      : Resource[F, Stream[F, Byte]] = {
    // Convert the pathy Path to a POSIX path, dropping
    // the first slash, which is what S3 expects for object paths
    val objectPath = Path.posixCodec.printPath(file).drop(1)
    // Put the object path after the bucket URI
    val queryUri = appendPathS3Encoded(uri, objectPath)
    val request = Request[F](uri = queryUri)

    Resource.liftF(Ref.of[F, ByteState](ByteState(0, 0, false))).flatMap(ref =>
      streamRequest[F](client, request, file, ref))
  }

  ////

  private def recordSeenBytes[F[_]: Functor](ref: Ref[F, ByteState])
      : Pipe[F, Byte, Byte] =
    _.chunks
      .evalTap(chunk => ref.getAndUpdate(s => s.copy(current = s.current + chunk.size)))
      .flatMap(Stream.chunk(_))

  //private case class ByteState(seen: Long, continue: Boolean)
  private case class ByteState(previous: Long, current: Long, continue: Boolean)

  private def streamRequest[F[_]: Sync: MonadResourceErr](
      client: Client[F], req: Request[F], file: AFile, ref: Ref[F, ByteState])
      : Resource[F, Stream[F, Byte]] =
    client.run(req).evalMap[F, Stream[F, Byte]](res => res.status match {
      case Status.NotFound =>
        MonadResourceErr[F].raiseError(ResourceError.pathNotFound(ResourcePath.leaf(file)))

      case Status.Forbidden =>
        MonadResourceErr[F].raiseError(accessDeniedError(ResourcePath.leaf(file)))

      case Status.Ok =>
        val current: Stream[F, Byte] = res.body.through(recordSeenBytes[F](ref)) onFinalizeCase {
          case ExitCase.Error(e) =>
            ref.update(s => s.copy(previous = s.current, continue = false)) >>
              MonadResourceErr[F].raiseError[Unit](ResourceError.connectionFailed(
                ResourcePath.leaf(file),
                Some("Unexpected response stream termination."),
                Some(e)))

          case ExitCase.Completed =>
            ref.update(_.copy(continue = false))

          case ExitCase.Canceled =>
            ref.update(_.copy(continue = true))
        }

        val next: Resource[F, Stream[F, Byte]] = Resource.liftF(ref.get) flatMap { state =>
          if (state.continue) {
            val newReq =
              req.withHeaders(Headers.of(
                `Content-Range`(RangeUnit.Bytes, SubRange(state.current, None), None)))

            streamRequest[F](client, newReq, file, ref)
          } else {
            Resource.pure[F, Stream[F, Byte]](Stream.empty)
          }
        }

        for {
          cur <- Resource.pure[F, Stream[F, Byte]](current)
          nxt <- next
        } yield {
          val x: Stream[F, Byte] = cur ++ nxt
          x
        }

      case other =>
        MonadResourceErr[F].raiseError(unexpectedStatusError(
          ResourcePath.leaf(file),
          other))
    })
}
