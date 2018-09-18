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
package impl

import quasar.api.resource.ResourcePath
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.contrib.pathy._
import quasar.physical.s3.S3JsonParsing

import slamdata.Predef._

import cats.data.OptionT
import cats.effect.{Effect, Sync}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.option._

import fs2.{Pipe, Stream}
import jawn.{Facade, ParseException}
import jawnfs2._
import org.http4s.client._
import org.http4s.{Request, Response, Status, Uri}
import pathy.Path
import shims._

object evaluate {

  def apply[F[_]: Effect, R: Facade](
      jsonParsing: S3JsonParsing,
      client: Client[F],
      uri: Uri,
      file: AFile,
      sign: Request[F] => F[Request[F]])
      (implicit MR: MonadResourceErr[F])
      : F[Option[Stream[F, R]]] = {
    // Convert the pathy Path to a POSIX path, dropping
    // the first slash, which is what S3 expects for object paths
    val objectPath = Path.posixCodec.printPath(file).drop(1)

    // Put the object path after the bucket URI
    val queryUri = appendPathUnencoded(uri, objectPath)
    val request = Request[F](uri = queryUri)

    sign(request) >>= { req =>
      val stream = OptionT(
        streamRequest[F, R](client, req) { resp =>
          resp.body.chunks.map(_.toByteBuffer).through(parse(jsonParsing))
        })

      stream.map(_.handleErrorWith {
        case ParseException(message, _, _, _) =>
          Stream.eval(MR.raiseError(parseError(file, jsonParsing, message)))
      }).value
    }
  }

  ////

  private def parse[F[_], R: Facade](jsonParsing: S3JsonParsing)
      : Pipe[F, ByteBuffer, R] =
    jsonParsing match {
      case S3JsonParsing.JsonArray => unwrapJsonArray[F, ByteBuffer, R]
      case S3JsonParsing.LineDelimited => parseJsonStream[F, ByteBuffer, R]
    }

  private def parseError(path: AFile, parsing: S3JsonParsing, message: String)
      : ResourceError = {
    val msg: String =
      s"Could not parse the file as JSON. Ensure you've configured the correct jsonParsing option for this bucket: $message"

    val expectedFormat: String = parsing match {
      case S3JsonParsing.LineDelimited => "Newline-delimited JSON"
      case S3JsonParsing.JsonArray => "Array-wrapped JSON"
    }

    ResourceError.malformedResource(
      ResourcePath.Leaf(path),
      expectedFormat,
      msg)
  }

  // there is no method in http4s 0.16.6a that does what we
  // want here, so we have to implement it ourselves.
  // what we want specifically is to make an HTTP request,
  // take the response, if it's a 404 return `None`,
  // if it's `Some(resp)` we compute an fs2 stream from
  // it using `f` and then call `dispose` on that response
  // once we've finished streaming.
  private def streamRequest[F[_]: Sync, A](
      client: Client[F], req: Request[F])(
      f: Response[F] => Stream[F, A])
      : F[Option[Stream[F, A]]] =
    client.open(req).flatMap {
      case DisposableResponse(response, dispose) =>
        response.status match {
          case Status.NotFound => none.pure[F]
          case Status.Ok => f(response).onFinalize(dispose).some.pure[F]
          case s => Sync[F].raiseError(new Exception(s"Unexpected status $s"))
        }
    }
}
