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
import quasar.api.resource.ResourcePath
import quasar.connector.{CompressionScheme, Datasource, QueryResult}
import quasar.physical.s3.SecureS3DatasourceSpec._

import cats.effect.IO
import cats.syntax.flatMap._
import fs2.Stream
import org.http4s.Uri
import shims._

final class GzipS3DatasourceSpec extends S3DatasourceSpec {
  override val testBucket = Uri.uri("https://s3.amazonaws.com/slamdata-public-gzip-test")

  override def assertResultBytes(
      ds: Datasource[IO, Stream[IO, ?], ResourcePath, QueryResult[IO]],
      path: ResourcePath,
      expected: Array[Byte]) =
    ds.evaluate(path) flatMap {
      case QueryResult.Compressed(CompressionScheme.Gzip, QueryResult.Typed(_, data)) =>
        // not worth checking the exact data here since it's still just transferring the exact byte stream
        // (as with non-gzipped configs)
        IO(ok)

      case _ =>
        IO(ko("Unexpected QueryResult"))
    }

  override val datasourceLD =
    run(credentials >>= (creds => mkDatasource[IO](S3Config(testBucket, S3JsonParsing.LineDelimited, Some(CompressionScheme.Gzip), creds))))
  override val datasource =
    run(credentials >>= (creds => mkDatasource[IO](S3Config(testBucket, S3JsonParsing.JsonArray, Some(CompressionScheme.Gzip), creds))))
}


