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

import slamdata.Predef._
import quasar.api.resource.ResourcePath
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.contrib.pathy._
import quasar.physical.s3.S3JsonParsing


import cats.effect.{Effect, Sync}
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.ApplicativeError

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
      : F[Stream[F, R]] = {
    // Convert the pathy Path to a POSIX path, dropping
    // the first slash, which is what S3 expects for object paths
    val objectPath = Path.posixCodec.printPath(file).drop(1)
    // Put the object path after the bucket URI
    val queryUri = appendPathS3Encoded(uri, objectPath)
    val request = Request[F](uri = queryUri)

    sign(request) >>= { req =>
      streamRequest[F, R](client, req, file) { resp =>
        resp.body.chunks.map(_.toByteBuffer).through(parse(jsonParsing))
      }.map(_.handleErrorWith {
        case ParseException(message, _, _, _) =>
          Stream.eval(MR.raiseError(parseError(file, jsonParsing, message)))
      })
    }
  }

  ////

  private def parse[F[_]: ApplicativeError[?[_], Throwable], R: Facade](jsonParsing: S3JsonParsing)
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

  private def streamRequest[F[_]: Sync: MonadResourceErr, A](
      client: Client[F], req: Request[F], file: AFile)(
      f: Response[F] => Stream[F, A])
      (implicit MR: MonadResourceErr[F])
      : F[Stream[F, A]] = {
    client.run(req).use(resp => resp.status match {
      case Status.NotFound => MR.raiseError(ResourceError.pathNotFound(ResourcePath.Leaf(file)))
      case Status.Ok => f(resp).pure[F]
      case s => Sync[F].raiseError(new Exception(s"Unexpected status $s"))
    })
  }
}
