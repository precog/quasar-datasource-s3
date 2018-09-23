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

import slamdata.Predef._

import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.common.data.Data
import quasar.connector.{Datasource, DatasourceSpec, MonadResourceErr, ResourceError}
import quasar.connector.ResourceError
import quasar.contrib.scalaz.MonadError_

import cats.data.{EitherT, OptionT}
import cats.effect.{Effect, IO}
import cats.syntax.applicative._
import cats.syntax.functor._
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import scalaz.{Id, ~>}, Id.Id
import shims._

import S3DataSourceSpec._

class S3DataSourceSpec extends DatasourceSpec[IO, Stream[IO, ?]] {
  val testBucket = Uri.uri("https://s3.amazonaws.com/slamdata-public-test")
  val nonExistentPath =
    ResourcePath.root() / ResourceName("does") / ResourceName("not") / ResourceName("exist")

  val spanishResourcePrefix = ResourcePath.root() / ResourceName("testData") / ResourceName("El veloz murciélago hindú") / ResourceName("comía feliz cardillo y kiwi") / ResourceName("La cigüeña tocaba el saxofón")
  val spanishResourceLeaf = ResourceName("detrás del palenque de paja")
  val spanishResource = spanishResourcePrefix / spanishResourceLeaf

  "pathIsResource" >> {
    "the root of a bucket with a trailing slash is not a resource" >>* {
      val root = ResourcePath.root() / ResourceName("")
      datasource.pathIsResource(root).map(_ must beFalse)
    }

    "the root of a bucket is not a resource" >>* {
      val root = ResourcePath.root()
      datasource.pathIsResource(root).map(_ must beFalse)
    }

    "a prefix without contents is not a resource" >>* {
      val path = ResourcePath.root() / ResourceName("prefix3") / ResourceName("subprefix5")
      datasource.pathIsResource(path).map(_ must beFalse)
    }

    "an actual file is a resource" >>* {
      val res = ResourcePath.root() / ResourceName("testData") / ResourceName("array.json")
      datasource.pathIsResource(res) map (_ must beTrue)
    }

    "an actual file with special chars in path is a resource" >>* {
      val res = ResourcePath.root() / ResourceName("testData") / ResourceName("á") / ResourceName("βç.json")
      datasource.pathIsResource(res) map (_ must beTrue)
    }

    "an actual file with special chars in deeper path is a resource" >>* {
      datasource.pathIsResource(spanishResource) map (_ must beTrue)
    }
  }

  "prefixedChildPaths" >> {

    "list nested children" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("dir1") / ResourceName("dir2") / ResourceName("dir3"),
        List(ResourceName("flattenable.data") -> ResourcePathType.leafResource))
    }

    "list children at the root of the bucket" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root(),
        List(
          ResourceName("dir1") -> ResourcePathType.prefix,
          ResourceName("extraSmallZips.data") -> ResourcePathType.leafResource,
          ResourceName("prefix3") -> ResourcePathType.prefix,
          ResourceName("testData") -> ResourcePathType.prefix))
    }

    "list resource with special chars" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("dir1"),
        List(
          ResourceName("dir2") -> ResourcePathType.prefix,
          ResourceName("fóóbar.ldjson") -> ResourcePathType.leafResource))
    }

    "list resource with special chars in path with special chars" >>* {
      assertPrefixedChildPaths(
        spanishResourcePrefix,
        List(spanishResourceLeaf -> ResourcePathType.leafResource))
    }
  }

  "evaluate" >> {
    "read line-delimited JSON" >>* {
      assertEvaluate(
        datasourceLD,
        ResourcePath.root() / ResourceName("testData") / ResourceName("lines.json"),
        data_12_34)
    }

    "read array JSON" >>* {
      assertEvaluate(
        datasource,
        ResourcePath.root() / ResourceName("testData") / ResourceName("array.json"),
        data_12_34)
    }

    "read array JSON of resource with special chars in path" >>* {
      assertEvaluate(
        datasource,
        ResourcePath.root() / ResourceName("testData") / ResourceName("á") / ResourceName("βç.json"),
        data_12_34)
    }

    "read line-delimited JSON with special chars of resource with special chars in path" >>* {
      assertEvaluate(
        datasourceLD,
        spanishResource,
        List(Data.Str("El veloz murciélago hindú comía feliz cardillo y kiwi. La cigüeña tocaba el saxofón detrás del palenque de paja.")))
    }

    "reading a non-existent file raises ResourceError.PathNotFound" >> {
      val creds = EitherT.right[Throwable](credentials)
      val ds = creds.flatMap(c => mkDatasource[G](S3JsonParsing.JsonArray, testBucket, c))

      val path = ResourcePath.root() / ResourceName("does-not-exist")
      val read: Stream[G, Data] = Stream.force(ds.flatMap(_.evaluator[Data].evaluate(path)))

      read.compile.toList.value.unsafeRunSync must beLeft.like {
        case ResourceError.throwableP(ResourceError.PathNotFound(_)) => ok
      }
    }
  }

  def assertEvaluate(ds: Datasource[IO, Stream[IO,?], ResourcePath], path: ResourcePath, expected: List[Data]) =
    ds.evaluator[Data].evaluate(path).flatMap { res =>
      gatherMultiple(res).map { _ must_== expected }
    }

  def assertPrefixedChildPaths(path: ResourcePath, expected: List[(ResourceName, ResourcePathType)]) =
    OptionT(datasource.prefixedChildPaths(path))
      .getOrElseF(IO.raiseError(new Exception(s"Failed to list resources under $path")))
      .flatMap(gatherMultiple(_)).map { _ must_== expected }

  def gatherMultiple[A](g: Stream[IO, A]) = g.compile.toList

  val data_12_34 = List(Data.Arr(List(Data.Int(1), Data.Int(2))), Data.Arr(List(Data.Int(3), Data.Int(4))))

  def credentials: IO[Option[S3Credentials]] = None.pure[IO]

  val run = λ[IO ~> Id](_.unsafeRunSync)

  def mkDatasource[F[_]: Effect: MonadResourceErr](
    parsing: S3JsonParsing,
    bucket: Uri,
    creds: Option[S3Credentials])
      : F[Datasource[F, Stream[F, ?], ResourcePath]] =
    Http1Client[F]().map(client =>
     new S3DataSource[F](client, S3Config(bucket, parsing, creds)))

  val datasourceLD = run(mkDatasource[IO](S3JsonParsing.LineDelimited, testBucket, None))
  val datasource = run(mkDatasource[IO](S3JsonParsing.JsonArray, testBucket, None))
}

object S3DataSourceSpec {
  type G[A] = EitherT[IO, Throwable, A]

  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)

  implicit val eitherTMonadResourceErr: MonadError_[G, ResourceError] =
    MonadError_.facet[G](ResourceError.throwableP)
}
