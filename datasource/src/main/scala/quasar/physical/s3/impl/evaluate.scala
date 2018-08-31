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

import quasar.common.data.Data
import quasar.contrib.pathy._
import quasar.physical.s3.S3JsonParsing
import quasar.run.QuasarError
import quasar.connector.ResourceError
import quasar.api.resource.ResourcePath

import slamdata.Predef._

import cats.Show
import cats.data.OptionT
import cats.effect.{Effect, Timer, Sync}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.option._
import fs2.{Pipe, Stream}
import io.circe.{Json, ParsingFailure}
import io.circe.fs2.{byteArrayParser, byteStreamParser}
import org.http4s.client._
import org.http4s.{Request, Response, Status, Uri}
import pathy.Path
import shims._

object evaluate {
  // circe's streaming parser, which we select based on the
  // passed S3JsonParsing
  private def circePipe[F[_]](jsonParsing: S3JsonParsing): Pipe[F, Byte, Json] = jsonParsing match {
    case S3JsonParsing.JsonArray => byteArrayParser[F]
    case S3JsonParsing.LineDelimited => byteStreamParser[F]
  }

  // as it says on the tin, converts circe's JSON type to
  // quasar's Data type
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def circeJsonToData(json: Json): Data = {
    json.fold(
      Data.Null,
      Data.Bool,
      n => Data.Dec(n.toBigDecimal.getOrElse(n.toDouble)),
      Data.Str,
      js => Data.Arr(js.map(circeJsonToData)(scala.collection.breakOut)),
      js => Data.Obj(ListMap(js.toList.map { case (k, v) => k -> circeJsonToData(v) }: _*))
    )
  }

  // there is no method in http4s 0.16.6a that does what we
  // want here, so we have to implement it ourselves.
  // what we want specifically is to make an HTTP request,
  // take the response, if it's a 404 return `None`,
  // if it's `Some(resp)` we compute an fs2 stream from
  // it using `f` and then call `dispose` on that response
  // once we've finished streaming.
  private def streamRequestThroughFs2[F[_]: Sync, A](client: Client[F], req: Request[F])(f: Response[F] => Stream[F, A]): F[Option[Stream[F, A]]] = {
    client.open(req).flatMap {
      case DisposableResponse(response, dispose) =>
        response.status match {
          case Status.NotFound => none.pure[F]
          case Status.Ok => f(response).onFinalize(dispose).some.pure[F]
          case s => Sync[F].raiseError(new Exception(s"Unexpected status $s"))
        }
    }
  }

  private def noParseError(path: AFile, parsing: S3JsonParsing, message: String): QuasarError = {
    val noParseMsg = "Could not parse the file as JSON. Ensure you've configured the correct jsonParsing option for this bucket"

    QuasarError.evaluating(
      ResourceError.malformedResource(
        ResourcePath.Leaf(path),
        Show[S3JsonParsing].show(parsing),
        noParseMsg,
        List(message)))
  }
  // putting it all together.
  def apply[F[_]: Effect: Timer](
    jsonParsing: S3JsonParsing,
    client: Client[F],
    uri: Uri,
    file: AFile,
    sign: Request[F] => F[Request[F]]): F[Option[Stream[F, Data]]] = {
    // convert the pathy Path to a POSIX path, dropping
    // the first slash, like S3 expects for object paths
    val objectPath = Path.posixCodec.printPath(file).drop(1)

    // Put the object path after the bucket URI
    val queryUri = appendPathUnencoded(uri, objectPath)
    val request = Request[F](uri = queryUri)

    // figure out how we're going to parse the object as JSON
    val circeJsonPipe = circePipe[F](jsonParsing)

    sign(request) >>= { r =>
      val stream =
        OptionT(streamRequestThroughFs2[F, Data](client, r) { resp =>
          // convert the data to JSON, using the parsing method
          // of our choice
          val asJson: Stream[F, Json] = resp.body.through(circeJsonPipe)

          // convert the JSON from circe's representation to ours
          val asData: Stream[F, Data] = asJson.map(circeJsonToData)

          // and we're done.
          asData
        })

      stream.map(_.handleErrorWith {
        case ParsingFailure(message, _) =>
          Stream.raiseError(
            QuasarError.throwableP(noParseError(file, jsonParsing, message)))
      }).value
    }
  }
}
