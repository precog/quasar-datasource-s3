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
import quasar.Data
import quasar.api.{DataSourceType, ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.datasource.LightweightDataSource
import quasar.api.ResourceError
import quasar.api.DataSourceError.CommonError
import quasar.api.ResourcePath.{Leaf, Root}
import org.http4s.client.Client
import org.http4s.Uri
import fs2.Stream
import eu.timepit.refined.auto._

import cats.effect.Sync
import scalaz.\/
import scalaz.syntax.applicative._
import scalaz.syntax.either._

import shims._

class S3DataSource[F[_]: Sync] private (
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

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def children(path: ResourcePath): F[CommonError \/ Stream[F, (ResourceName, ResourcePathType)]] =
    path match {
      case Root => ???
      case l @ Leaf(_) =>
        impl.children(client, bucket, l.toPath) >>= {
          case None => ???
          case Some(paths) => ???
        }
    }

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def descendants(path: ResourcePath): F[CommonError \/ Stream[F, ResourcePath]] = ???

  def isResource(path: ResourcePath): F[Boolean] = path match {
    case Root => false.point[F]
    case Leaf(file) => impl.exists(client, bucket, file)
  }
}
