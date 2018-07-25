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

import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath.{Leaf, Root}
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.common.data.Data
import quasar.connector.MonadResourceErr
import quasar.connector.datasource.LightweightDatasource
import quasar.contrib.pathy.APath

import scala.concurrent.duration.SECONDS
import slamdata.Predef.{Stream => _, _}

import java.time.{ZoneOffset, LocalDateTime}

import cats.effect.{Effect, Timer}
import cats.syntax.flatMap._
import cats.syntax.option._
import fs2.Stream
import org.http4s.{Request, Header, Headers}
import org.http4s.client.Client
import pathy.Path
import pathy.Path.{DirName, FileName}
import scalaz.syntax.applicative._
import scalaz.{\/-, -\/}
import shims._

final class S3DataSource[F[_]: Effect: Timer: MonadResourceErr](
  client: Client[F],
  config: S3Config)
    extends LightweightDatasource[F, Stream[F, ?], Stream[F, Data]] {
  def kind: DatasourceType = s3.datasourceKind

  def evaluate(path: ResourcePath): F[Stream[F, Data]] =
    path match {
      case Root =>
        Stream.empty.covaryAll[F, Data].pure[F]
      case Leaf(file) =>
        impl.evaluate[F](config.parsing, client, config.bucket, file, S3DataSource.signRequest(config)) map {
          case None => Stream.empty
          /* In http4s, the type of streaming results is the same as
           every other effectful operation. However,
           LightweightDatasourceModule forces us to separate the types,
           so we need to translate */
          case Some(s) => s
        }
    }

  def prefixedChildPaths(path: ResourcePath): F[Option[Stream[F, (ResourceName, ResourcePathType)]]] =
    impl.children(
      client,
      config.bucket,
      dropEmpty(path.toPath),
      S3DataSource.signRequest(config)) map {
      case None =>
        none[Stream[F, (ResourceName, ResourcePathType)]]
      case Some(paths) =>
        paths.map {
          case -\/(Path.DirName(dn)) => (ResourceName(dn), ResourcePathType.prefix)
          case \/-(Path.FileName(fn)) => (ResourceName(fn), ResourcePathType.leafResource)
        }.some
    }

  def pathIsResource(path: ResourcePath): F[Boolean] = path match {
    case Root => false.pure[F]
    case Leaf(file) => Path.refineType(dropEmpty(file)) match {
      case -\/(_) => false.pure[F]
      case \/-(f) => impl.isResource(client, config.bucket, f, S3DataSource.signRequest(config))
    }
  }

  private def dropEmpty(path: APath): APath =
    Path.peel(path) match {
      case Some((d, \/-(FileName(fn)))) if fn.isEmpty => d
      case Some((d, -\/(DirName(dn)))) if dn.isEmpty => d
      case _ => path
    }
}

object S3DataSource {
  def signRequest[F[_]: Effect: Timer](c: S3Config): Request[F] => F[Request[F]] =
    c.credentials match {
      case Some(creds) => {
        val requestSigning = for {
          seconds <- Timer[F].clockRealTime(SECONDS)
          datetime <- Effect[F].catchNonFatal(LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC))
          signing = RequestSigning(
            Credentials(creds.accessKey, creds.secretKey, None),
            creds.region,
            ServiceName.S3,
            PayloadSigning.Signed,
            datetime)
        } yield signing

        req => {
          // Requests that require signing also require `host` to always be present
          val req0 = req.uri.host match {
            case Some(host) => req.withHeaders(Headers(Header("host", host.value)))
            case None => req
          }

          requestSigning >>= (s => s.signedHeaders[F](req0).map(h => req0.withHeaders(h)))
        }
      }
      case None => req => req.pure[F]
    }
}
