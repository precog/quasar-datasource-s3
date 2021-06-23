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

import slamdata.Predef._

import quasar.connector.DataFormat

import scala.io.{Source, Codec}

import java.io.File

import argonaut.{Parse, DecodeJson}

import cats.effect.{IO, Resource}
import cats.syntax.flatMap._
import cats.syntax.applicative._
import cats.syntax.option._

import org.http4s.Uri

final class SecureS3DatasourceSpec extends S3DatasourceSpec {
  override val testBucket = Uri.uri("https://slamdata-private-test.s3.amazonaws.com")

  override val credentials: IO[Option[S3Credentials]] = {
    val read = IO {
      val file = new File(credsFile)
      val src = Source.fromFile(file)(Codec.UTF8)

      (src.getLines.mkString, src)
    }

    read.bracket({
      case (p, _) => {
        val msg = "Failed to read testCredentials.json"
        val jsonConfig =
          Parse.parse(p).toOption.map(_.pure[IO]).getOrElse(IO.raiseError(new Exception(msg)))

        jsonConfig
          .map(DecodeJson.of[S3Credentials].decodeJson(_))
          .map(_.toOption) >>= (_.fold[IO[Option[S3Credentials]]](IO.raiseError(new Exception(msg)))(c => c.some.pure[IO]))
      }
    })({
      case (_, src) => IO(src.close)
    })
  }

  private val credsFile = "testCredentials.json"

  override val datasourceLD =
    Resource.eval(credentials) flatMap { creds =>
      mkDatasource(S3Config(testBucket, DataFormat.ldjson, creds))
    }

  override val datasource =
    Resource.eval(credentials) flatMap { creds =>
      mkDatasource(S3Config(testBucket, DataFormat.json, creds))
    }

  override val datasourceCSV =
    Resource.eval(credentials) flatMap { creds =>
      mkDatasource(S3Config(testBucket, DataFormat.SeparatedValues.Default, creds))
    }
}
