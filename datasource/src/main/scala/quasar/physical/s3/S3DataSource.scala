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

import quasar.api.QueryEvaluator
import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath.{Leaf, Root}
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.connector.datasource.LightweightDatasource
import quasar.contrib.pathy.APath
import quasar.contrib.scalaz.MonadError_

import slamdata.Predef.{Stream => _, _}

import java.time.{OffsetDateTime, ZoneOffset, LocalDateTime}

import cats.effect.Effect
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import fs2.Stream
import jawn.Facade
import org.http4s.{Request, Header, Headers}
import org.http4s.client.Client
import pathy.Path
import pathy.Path.{DirName, FileName}
import qdata.QDataEncode
import qdata.json.QDataFacade
import scalaz.{\/-, -\/, OptionT}
import shims._

final class S3DataSource[F[_]: Effect: MonadResourceErr](
  client: Client[F],
  config: S3Config)
    extends LightweightDatasource[F, Stream[F, ?]] {
  def kind: DatasourceType = s3.datasourceKind

  def evaluator[R: QDataEncode]: QueryEvaluator[F, ResourcePath, Stream[F, R]] =
    new QueryEvaluator[F, ResourcePath, Stream[F, R]] {
      implicit val facade: Facade[R] = QDataFacade.qdata[R]

      val MR = MonadError_[F, ResourceError]

      def evaluate(path: ResourcePath): F[Stream[F, R]] =
        path match {
          case Root =>
            MR.raiseError(ResourceError.notAResource(path))
          case Leaf(file) =>
            impl.evaluate[F, R](config.parsing, client, config.bucket, file, signRequest(config))
        }
    }

  def prefixedChildPaths(path: ResourcePath): F[Option[Stream[F, (ResourceName, ResourcePathType)]]] =
    impl.children(
      client,
      config.bucket,
      dropEmpty(path.toPath),
      signRequest(config)) map {
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
      case \/-(f) => impl.isResource(client, config.bucket, f, signRequest(config))
    }
  }

  def isLive: F[Boolean] =
    OptionT(prefixedChildPaths(ResourcePath.Root)).isDefined

  //

  private def dropEmpty(path: APath): APath =
    Path.peel(path) match {
      case Some((d, \/-(FileName(fn)))) if fn.isEmpty => d
      case Some((d, -\/(DirName(dn)))) if dn.isEmpty => d
      case _ => path
    }

  private def signRequest(c: S3Config): Request[F] => F[Request[F]] =
    S3DataSource.signRequest(c)
}

object S3DataSource {
  def signRequest[F[_]: Effect](c: S3Config): Request[F] => F[Request[F]] =
    c.credentials match {
      case Some(creds) => {
        val requestSigning = for {
          time <- Effect[F].delay(OffsetDateTime.now())
          datetime <- Effect[F].catchNonFatal(
            LocalDateTime.ofEpochSecond(time.toEpochSecond, 0, ZoneOffset.UTC))
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

          requestSigning >>= (_.signedHeaders[F](req0).map(req0.withHeaders(_)))
        }
      }
      case None => req => req.pure[F]
    }
}
