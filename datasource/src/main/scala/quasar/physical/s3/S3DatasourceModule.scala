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

import slamdata.Predef.{Stream => _, _}
import quasar.Disposable
import quasar.api.datasource.{DatasourceError, DatasourceType}
import quasar.api.datasource.DatasourceError.InitializationError
import quasar.api.resource.ResourcePath
import quasar.connector.{Datasource, LightweightDatasourceModule, MonadResourceErr, QueryResult}
import quasar.physical.s3.S3Datasource.{Live, NotLive, Redirected}

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext

import argonaut.Json
import argonaut.ArgonautScalaz._
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import fs2.Stream
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import scalaz.{NonEmptyList, \/}
import scalaz.syntax.either._
import scalaz.syntax.functor._
import shims._

object S3DatasourceModule extends LightweightDatasourceModule {

  private val MaxRedirects = 3
  private val Redacted = "<REDACTED>"
  private val RedactedCreds = S3Credentials(AccessKey(Redacted), SecretKey(Redacted), Region(Redacted))

  def kind: DatasourceType = s3.datasourceKind

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  def lightweightDatasource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
    config: Json)(implicit ec: ExecutionContext)
      : F[InitializationError[Json] \/ Disposable[F, Datasource[F, Stream[F, ?], ResourcePath, QueryResult[F]]]] =
    config.as[S3Config].result match {
      case Right(s3Config) =>
        mkClient(s3Config).flatMap { disposableClient =>
          val s3Ds = new S3Datasource[F](disposableClient.unsafeValue, s3Config)
          // FollowRediret is not mounted in mkClient because it interferes
          // with permanent redirect handling
          val redirectClient = FollowRedirect(MaxRedirects)(disposableClient.unsafeValue)

          s3Ds.isLive(MaxRedirects) map {
            case Redirected(newConfig) =>
              val ds: Datasource[F, Stream[F, ?], ResourcePath, QueryResult[F]] =
                new S3Datasource[F](redirectClient, newConfig)
              Disposable(ds, disposableClient.dispose).right
            case Live =>
              val ds: Datasource[F, Stream[F, ?], ResourcePath, QueryResult[F]] =
                new S3Datasource[F](redirectClient, s3Config)
              Disposable(ds, disposableClient.dispose).right
            case NotLive =>
              val msg = "Unable to ListObjects at the root of the bucket"
              DatasourceError
                .accessDenied[Json, InitializationError[Json]](kind, config, msg)
                .left
          }
        }

      case Left((msg, _)) =>
        DatasourceError
          .invalidConfiguration[Json, InitializationError[Json]](kind, config, NonEmptyList(msg))
          .left.pure[F]
    }

  override def sanitizeConfig(config: Json): Json = {
    val sanitized = for {
      creds <- config.cursor --\ "credentials"

      redacted =
        if (creds.focus === Json.jNull) creds
        else creds.set(S3Credentials.credentialsCodec.encode(RedactedCreds))
    } yield redacted.undo
    sanitized.getOrElse(config)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  private def mkClient[F[_]: ConcurrentEffect](conf: S3Config)
      (implicit ec: ExecutionContext)
      : F[Disposable[F, Client[F]]] = {
    val clientResource = BlazeClientBuilder[F](ec)
      .withIdleTimeout(Duration.Inf)
      .resource
    val signingClient = clientResource.map(AwsV4Signing(conf))

    Disposable.fromResource(signingClient)
  }
}
