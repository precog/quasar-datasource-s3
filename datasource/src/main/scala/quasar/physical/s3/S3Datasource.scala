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
import quasar.connector.{MonadResourceErr, ParsableType, QueryResult, ResourceError}
import quasar.connector.datasource.LightweightDatasource
import quasar.contrib.pathy.APath
import quasar.contrib.scalaz.MonadError_

import slamdata.Predef.{Stream => _, _}

import cats.data.OptionT
import cats.effect.Effect
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.option._
import fs2.Stream
import org.http4s.client.Client
import pathy.Path
import pathy.Path.{DirName, FileName}
import scalaz.{\/-, -\/}
import shims._

final class S3Datasource[F[_]: Effect: MonadResourceErr](
    client: Client[F],
    config: S3Config)
    extends LightweightDatasource[F, Stream[F, ?], QueryResult[F]] {

  import ParsableType.JsonVariant

  def kind: DatasourceType = s3.datasourceKind

  def evaluate(path: ResourcePath): F[QueryResult[F]] =
    path match {
      case Root =>
        MonadError_[F, ResourceError].raiseError(ResourceError.notAResource(path))

      case Leaf(file) =>
        val jvar = config.parsing match {
          case S3JsonParsing.JsonArray => JsonVariant.ArrayWrapped
          case S3JsonParsing.LineDelimited => JsonVariant.LineDelimited
        }

        impl.evaluate[F](client, config.bucket, file)
          .map(QueryResult.typed(ParsableType.json(jvar, false), _))
    }

  def prefixedChildPaths(path: ResourcePath): F[Option[Stream[F, (ResourceName, ResourcePathType)]]] =
    impl.children(client, config.bucket, dropEmpty(path.toPath)) map {
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
      case \/-(f) => impl.isResource(client, config.bucket, f)
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
}
