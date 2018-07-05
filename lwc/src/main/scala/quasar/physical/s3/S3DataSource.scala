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

import eu.timepit.refined.auto._
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.Client
import quasar.Data
import quasar.api.ResourceError.CommonError
import quasar.api.ResourceError
import quasar.api.ResourcePath.{Leaf, Root}
import quasar.api.{DataSourceType, ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.datasource.LightweightDataSource
import pathy.Path
import slamdata.Predef.{Stream => _, _}

import cats.effect.Sync
import scalaz.{\/, \/-, -\/}
import scalaz.syntax.applicative._
import scalaz.syntax.either._

import shims._

final class S3DataSource[F[_]: Sync] (
  client: Client[F],
  bucket: Uri,
  s3JsonParsing: S3JsonParsing) extends LightweightDataSource[F, Stream[F, ?], Stream[F, Data]] {

  def kind: DataSourceType = DataSourceType("remote", 1L)

  val shutdown: F[Unit] = client.shutdown

  def evaluate(path: ResourcePath): F[ResourceError.ReadError \/ Stream[F, Data]] =
    path match {
      case Root => ResourceError.notAResource(path).left.point[F]
      case Leaf(file) => impl.read[F](s3JsonParsing, client, bucket, file) map {
        case None => (ResourceError.pathNotFound(Leaf(file)): ResourceError.ReadError).left
        case Some(s) => s.right
      }
    }


  def children(path: ResourcePath): F[CommonError \/ Stream[F, (ResourceName, ResourcePathType)]] =
    impl.children(client, bucket, path.toPath) map {
      case None => Stream.empty.covary[F].right[CommonError]
      case Some(paths) => Stream.emits(paths.toList.map {
        case -\/(Path.DirName(dn)) => (ResourceName(dn), ResourcePathType.ResourcePrefix)
        case \/-(Path.FileName(fn)) => (ResourceName(fn), ResourcePathType.Resource)
      }).covary[F].right[CommonError]
    }

  def descendants(path: ResourcePath): F[CommonError \/ Stream[F, ResourcePath]] =
    impl.children.descendants(client, bucket, path.toPath) map {
      case None => Stream.empty.covary[F].right[CommonError]
      case Some(paths) => Stream.emits(paths.map(ResourcePath.fromPath(_))).covary[F].right[CommonError]
    }

  def isResource(path: ResourcePath): F[Boolean] = path match {
    case Root => false.point[F]
    case Leaf(file) => impl.exists(client, bucket, file)
  }
}
