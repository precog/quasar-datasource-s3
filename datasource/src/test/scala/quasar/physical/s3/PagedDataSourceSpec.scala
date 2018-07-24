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

import cats.effect.IO
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client

final class PagedS3DataSourceSpec extends S3DataSourceSpec {
  // Force S3 to return a single element per page in ListBuckets,
  // to ensure pagination works correctly
  private val maxKeys = Map("max-keys" -> "2")

  override val discoveryLD = new S3DataSource[IO, IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-public-test"),
      S3JsonParsing.LineDelimited,
      None), maxKeys)(global)

  override val discovery = new S3DataSource[IO, IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-public-test"),
      S3JsonParsing.JsonArray,
      None), maxKeys)(global)
}
