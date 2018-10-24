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
import quasar.api.datasource.DatasourceError
import quasar.api.datasource.DatasourceError.InitializationError
import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath
import quasar.connector.Datasource
import quasar.connector.LightweightDatasourceModule
import quasar.connector.MonadResourceErr

import scala.concurrent.ExecutionContext

import argonaut.{EncodeJson, Json}
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import fs2.Stream
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import scalaz.{\/, NonEmptyList}
import scalaz.syntax.either._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.option._
import shims._
import slamdata.Predef.{Stream => _, _}

object S3DatasourceModule extends LightweightDatasourceModule {
  def kind: DatasourceType = s3.datasourceKind

  def lightweightDatasource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
    config: Json)(implicit ec: ExecutionContext)
      : F[InitializationError[Json] \/ Disposable[F, Datasource[F, Stream[F, ?], ResourcePath]]] =
    config.as[S3Config].result match {
      case Right(s3Config) =>
        val clientResource = BlazeClientBuilder[F](ec).resource

        clientResource.use { client =>
          val s3Ds = new S3Datasource[F](client, s3Config)
          val ds: Datasource[F, Stream[F, ?], ResourcePath] = s3Ds

          s3Ds.isLive.ifM({
            Disposable(ds, s3.resourceCleanup(clientResource)).right.pure[F]
          },
          {
            val msg = "Unable to ListObjects at the root of the bucket"

            DatasourceError
              .accessDenied[Json, InitializationError[Json]](kind, config, msg)
              .left.pure[F]
          })
        }

      case Left((msg, _)) =>
        DatasourceError
          .invalidConfiguration[Json, InitializationError[Json]](kind, config, NonEmptyList(msg))
          .left.pure[F]
    }

  def sanitizeConfig(config: Json): Json = {
    val redactedCreds =
      S3Credentials(
        AccessKey("<REDACTED>"),
        SecretKey("<REDACTED>"),
        Region("<REDACTED>"))

    config.as[S3Config].result.toOption.map((c: S3Config) =>
      // ignore the existing credentials and replace them with redactedCreds
      c.credentials.fold(c)(_ => c.copy(credentials = redactedCreds.some)))
      .fold(config)(rc => EncodeJson.of[S3Config].encode(rc))
  }
}
