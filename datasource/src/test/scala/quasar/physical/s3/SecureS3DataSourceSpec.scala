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

import slamdata.Predef._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.{Source, Codec}

import java.io.File

import argonaut.{Parse, DecodeJson}
import cats.effect.{IO, Sync}
import cats.syntax.flatMap._
import cats.syntax.applicative._
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client

final class SecureS3DataSourceSpec extends S3DataSourceSpec {
  override val discoveryLD = new S3DataSource[IO, IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-private-test"),
      S3JsonParsing.LineDelimited,
      Some(readCredentials.unsafeRunSync)))(global)

  override val discovery = new S3DataSource[IO, IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-private-test"),
      S3JsonParsing.JsonArray,
      Some(readCredentials.unsafeRunSync)))(global)

  // FIXME: close the file once we update to cats-effect 1.0.0 and
  // Bracket is available
  private def readCredentials: IO[S3Credentials] = {
    val file = Sync[IO].catchNonFatal(new File("testCredentials.json"))
    val msg = "Failed to read testCredentials.json"

    val src = (file >>= (f =>
      IO(Source.fromFile(f)(Codec.UTF8)))).map(_.getLines.mkString)

    val jsonConfig = src >>= (p =>
      Parse.parse(p).toOption.map(_.pure[IO])
        .getOrElse(IO.raiseError(new Exception(msg))))

    jsonConfig
      .map(DecodeJson.of[S3Credentials].decodeJson(_))
      .map(_.toOption) >>= (_.fold[IO[S3Credentials]](IO.raiseError(new Exception(msg)))(_.pure[IO]))
  }
}
