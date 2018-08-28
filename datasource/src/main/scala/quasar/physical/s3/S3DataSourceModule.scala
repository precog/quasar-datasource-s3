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


import quasar.Disposable
import quasar.api.datasource.DatasourceError.{
  InitializationError,
  InvalidConfiguration,
  MalformedConfiguration
}
import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath
import quasar.common.data.Data
import quasar.connector.Datasource
import quasar.connector.LightweightDatasourceModule
import quasar.connector.MonadResourceErr

import argonaut.{EncodeJson, Json}
import cats.effect.{Timer, ConcurrentEffect}
import fs2.Stream
import org.http4s.client.blaze.Http1Client
import scalaz.{\/, NonEmptyList}
import scalaz.syntax.either._
import scalaz.syntax.applicative._
import scalaz.syntax.bind._
import scalaz.syntax.functor._
import scalaz.syntax.std.option._
import scalaz.std.option._
import shims._
import slamdata.Predef.{Stream => _, _}

object S3DataSourceModule extends LightweightDatasourceModule {
  def kind: DatasourceType = s3.datasourceKind

  def lightweightDatasource[F[_]: ConcurrentEffect: MonadResourceErr: Timer](config: Json)
      : F[InitializationError[Json] \/ Disposable[F, Datasource[F, Stream[F, ?], ResourcePath, Stream[F, Data]]]] = {
    config.as[S3Config].result match {
      case Right(s3Config) => {
        Http1Client[F]() flatMap { client =>
          val s3Ds = new S3DataSource[F](client, s3Config)
          val ds: Datasource[F, Stream[F, ?], ResourcePath, Stream[F, Data]] = s3Ds

          s3Ds.isLive.ifM({
            val disposable = Disposable(ds, client.shutdown)

            disposable.right[InitializationError[Json]].point[F]
          }, {
            val msg = "Unable to ListObjects at the root of the bucket"
            val err: InitializationError[Json] =
              InvalidConfiguration(s3.datasourceKind, config, NonEmptyList(msg))

            err.left[Disposable[F, Datasource[F, Stream[F, ?], ResourcePath, Stream[F, Data]]]].point[F]
          })
        }
      }

      case Left((msg, _)) =>
        (MalformedConfiguration(kind, config, msg): InitializationError[Json])
          .left[Disposable[F, Datasource[F, Stream[F, ?], ResourcePath, Stream[F, Data]]]].point[F]
    }
  }

  def sanitizeConfig(config: Json): Json = {
    val redactedCreds =
      S3Credentials(
        AccessKey("<REDACTED>"),
        SecretKey("<REDACTED>"),
        Region("<REDACTED>")).some

    val redacted = config.as[S3Config].flatMap(c =>
      c.credentials.as(c.copy(credentials = redactedCreds)))

    EncodeJson.of[S3Config]
      .encode[S3Config](redacted).toOption.getOrElse(config)
  }

}
