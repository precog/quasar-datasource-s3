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

import quasar.Data
import quasar.contrib.pathy._
import quasar.physical.s3.S3JsonParsing
import slamdata.Predef._

import fs2.{Pipe, Stream}
import io.circe.Json
import org.http4s.client._
import org.http4s.{Request, Response, Status, Uri}
import pathy.Path
import scalaz.Scalaz._
import scalaz.concurrent.Task
import scodec.bits.ByteVector

object read {

  private def circePipe[F[_]](jsonParsing: S3JsonParsing): Pipe[F, String, Json] = jsonParsing match {
    case S3JsonParsing.JsonArray => parsing.stringArrayParser[F]
    case S3JsonParsing.LineDelimited => parsing.stringStreamParser[F]
  }

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

  private def streamRequestThroughFs2[A](client: Client, req: Request)(f: Response => Stream[Task, A]): Task[Option[Stream[Task, A]]] = {
    client.open(req).flatMap {
      case DisposableResponse(response, dispose) =>
        response.status match {
          case Status.NotFound => Task.now(none)
          case Status.Ok => Task.now(f(response).onFinalize[Task](dispose).some)
          case s => Task.fail(new Exception(s"Unexpected status $s"))
        }
    }
  }

  def apply(jsonParsing: S3JsonParsing, client: Client, uri: Uri, file: AFile): Task[Option[Stream[Task, Data]]] = {
    val objectPath = Path.posixCodec.printPath(file).drop(1)
    val queryUri = uri / objectPath
    val request = Request(uri = queryUri)
    val circeJsonPipe = circePipe[Task](jsonParsing)
    streamRequestThroughFs2(client, request) { resp =>
      // TODO: can this fail? I don't believe so.
      val asFs2: Stream[Task, ByteVector] = fs2Conversion.processToFs2(resp.body)
      // TODO: a failure point
      val asStrings: Stream[Task, String] = asFs2.evalMap(_.decodeUtf8.fold(Task.fail, Task.now))
      // TODO: another possible failure
      val asJson: Stream[Task, Json] = asStrings.through(circeJsonPipe)
      val asData: Stream[Task, Data] = asJson.map(circeJsonToData)
      asData
    }
  }
}