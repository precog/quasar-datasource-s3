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

import quasar.RateLimiting
import quasar.api.datasource.{DatasourceError, DatasourceType}
import quasar.api.datasource.DatasourceError.{ConfigurationError, InitializationError}
import quasar.connector.{ByteStore, MonadResourceErr}
import quasar.connector.datasource.{LightweightDatasourceModule, Reconfiguration}
import quasar.physical.s3.S3Datasource.{Live, NotLive, Redirected}

import scala.concurrent.ExecutionContext
import scala.util.Either

import argonaut.{Json, Argonaut}, Argonaut._
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.kernel.Hash
import cats.implicits._
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import scalaz.NonEmptyList
import shims._

object S3DatasourceModule extends LightweightDatasourceModule {

  private val MaxRedirects = 3

  def kind: DatasourceType = s3.datasourceKind

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  def lightweightDatasource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer, A: Hash](
      config: Json,
      rateLimiting: RateLimiting[F, A],
      byteStore: ByteStore[F])(
      implicit ec: ExecutionContext)
      : Resource[F, Either[InitializationError[Json], LightweightDatasourceModule.DS[F]]] =
    config.as[S3Config].result match {
      case Right(s3Config) =>
        mkClient(s3Config) evalMap { client =>
          val s3Ds = S3Datasource[F](client, s3Config)
          // FollowRediret is not mounted in mkClient because it interferes
          // with permanent redirect handling
          val redirectClient = FollowRedirect(MaxRedirects)(client)

          s3Ds.isLive(MaxRedirects) map {
            case Redirected(newConfig) =>
              Right(new S3Datasource[F](redirectClient, newConfig))

            case Live =>
              Right(new S3Datasource[F](redirectClient, s3Config))

            case NotLive =>
              val msg = "Unable to ListObjects at the root of the bucket"
              Left(DatasourceError
                .accessDenied[Json, InitializationError[Json]](kind, sanitizeConfig(config), msg))
          }
        }

      case Left((msg, _)) =>
        DatasourceError
          .invalidConfiguration[Json, InitializationError[Json]](kind, sanitizeConfig(config), NonEmptyList(msg))
          .asLeft[LightweightDatasourceModule.DS[F]]
          .pure[Resource[F, ?]]
    }

  def reconfigure(originalJson: Json, patchJson: Json): Either[ConfigurationError[Json], (Reconfiguration, Json)] = {
    val back = for {
      original <- originalJson.as[S3Config].result.leftMap(_ =>
        DatasourceError
          .MalformedConfiguration[Json](
            kind,
            sanitizeConfig(originalJson),
            "Source configuration in reconfiguration is malformed."))

      patch <- patchJson.as[S3Config].result.leftMap(_ =>
        DatasourceError
          .MalformedConfiguration[Json](
            kind,
            sanitizeConfig(patchJson),
            "Patch configuration in reconfiguration is malformed."))

      reconfigured <- original.reconfigure(patch).leftMap(c =>
        DatasourceError.InvalidConfiguration[Json](
          kind,
          c.asJson,
          NonEmptyList("Patch configuration contains sensitive information.")))
    } yield reconfigured.asJson

    back.tupleLeft(Reconfiguration.Reset)
  }

  override def sanitizeConfig(config: Json): Json = config.as[S3Config].result match {
    case Left(_) =>
      config
    case Right(cfg) =>
      cfg.sanitize.asJson
  }

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  private def mkClient[F[_]: ConcurrentEffect](conf: S3Config)
      : Resource[F, Client[F]] =
    AsyncHttpClientBuilder[F].map[F, Client[F]](AwsV4Signing(conf))
}
