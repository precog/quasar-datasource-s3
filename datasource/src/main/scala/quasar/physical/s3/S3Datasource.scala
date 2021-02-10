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

import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath.{Leaf, Root}
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.{MonadResourceErr, QueryResult, ResourceError, ResultData}
import quasar.connector.datasource.{BatchLoader, LightweightDatasource, Loader}
import quasar.contrib.scalaz.MonadError_
import quasar.qscript.InterpretedRead

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Resource, Sync}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import fs2.Stream
import org.http4s.client.Client
import pathy.Path
import scalaz.{\/-, -\/}
import shims._

final class S3Datasource[F[_]: Sync: MonadResourceErr](
    client: Client[F],
    config: S3Config)
    extends LightweightDatasource[Resource[F, ?], Stream[F, ?], QueryResult[F]] {

  import S3Datasource._

  def kind: DatasourceType = s3.datasourceKind

  val loaders = NonEmptyList.of(Loader.Batch(BatchLoader.Full { (iRead: InterpretedRead[ResourcePath]) =>
    iRead.path match {
      case Root =>
        Resource.liftF(MonadError_[F, ResourceError].raiseError[QueryResult[F]](
          ResourceError.notAResource(iRead.path)))

      case Leaf(file) =>
        impl.evaluate[F](client, config.bucket, file) map { bytes =>
          QueryResult.typed(config.format, ResultData.Continuous(bytes), iRead.stages)
        }
    }
  }))

  def prefixedChildPaths(path: ResourcePath)
      : Resource[F, Option[Stream[F, (ResourceName, ResourcePathType.Physical)]]] =
    pathIsResource(path) evalMap {
      case true =>
        Stream.empty
          .covaryOutput[(ResourceName, ResourcePathType.Physical)]
          .covary[F].some.pure[F] // FIXME: static guarantees from pathIsResource

      case false =>
        impl.children(client, config.bucket, path.toPath) map {
          case None =>
            none[Stream[F, (ResourceName, ResourcePathType.Physical)]]
          case Some(paths) =>
            paths.map {
              case -\/(Path.DirName(dn)) => (ResourceName(dn), ResourcePathType.prefix)
              case \/-(Path.FileName(fn)) => (ResourceName(fn), ResourcePathType.leafResource)
            }.some
        }
    }

  def pathIsResource(path: ResourcePath): Resource[F, Boolean] =
    Resource.liftF(path match {
      case Root => false.pure[F]
      case Leaf(file) => Path.refineType(file) match {
        case -\/(_) => false.pure[F]
        case \/-(f) => impl.isResource(client, config.bucket, f)
      }
    })

  def isLive(maxRedirects: Int): F[Liveness] =
    impl.preflightCheck(client, config.bucket, maxRedirects) flatMap {
      case Some(newBucket) =>
        OptionT(impl.children(client, newBucket, Path.rootDir))
          .fold(Liveness.notLive)(_ =>
            if(newBucket === config.bucket)
              Liveness.live
            else
              Liveness.redirected(config.copy(bucket = newBucket)))
      case None =>
        Liveness.notLive.pure[F]
    }
}

object S3Datasource {
  sealed abstract class Liveness
  final case class Redirected(conf: S3Config) extends Liveness
  final case object Live extends Liveness
  final case object NotLive extends Liveness

  object Liveness {
    def live: Liveness = Live
    def notLive: Liveness = NotLive
    def redirected(conf: S3Config): Liveness = Redirected(conf)
  }

  def apply[F[_]: Sync: MonadResourceErr](client: Client[F], config: S3Config)
      : S3Datasource[F] =
    new S3Datasource[F](client, config)
}
