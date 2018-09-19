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
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import scalaz.syntax.applicative._
import scalaz.{Id, ~>}, Id.Id
import shims._

import S3DataSourceSpec._

class S3DataSourceSpec extends DatasourceSpec[IO, Stream[IO, ?]] {
  val testBucket = Uri.uri("https://s3.amazonaws.com/slamdata-public-test")
  val nonExistentPath =
    ResourcePath.root() / ResourceName("does") / ResourceName("not") / ResourceName("exist")


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

  "list nested children" >>* {
    val path = ResourcePath.root() / ResourceName("dir1") / ResourceName("dir2") / ResourceName("dir3")

    val listing = datasource.prefixedChildPaths(path)

    listing.flatMap { list =>
      list.map(gatherMultiple(_))
        .getOrElse(IO.raiseError(new Exception("Could not list nested children under dir1/dir2/dir3")))
        .map {
          case List((resource, resourceType)) =>
            resource must_= ResourceName("flattenable.data")
            resourceType must_= ResourcePathType.leafResource
        }
    }
  }

  "list children at the root of the bucket" >>* {
    datasource.prefixedChildPaths(ResourcePath.root()).flatMap { list =>
      list.map(gatherMultiple(_)).getOrElse(IO.raiseError(new Exception("Could not list children under the root")))
        .map(resources => {
          resources.length must_== 4
          resources(0) must_== (ResourceName("dir1") -> ResourcePathType.prefix)
          resources(1) must_== (ResourceName("extraSmallZips.data") -> ResourcePathType.leafResource)
          resources(2) must_== (ResourceName("prefix3") -> ResourcePathType.prefix)
          resources(3) must_== (ResourceName("testData") -> ResourcePathType.prefix)
        })
    }
  }

  "an actual file is a resource" >>* {
    val res = ResourcePath.root() / ResourceName("testData") / ResourceName("array.json")

    datasource.pathIsResource(res) map (_ must beTrue)
  }

  "read line-delimited and array JSON" >>* {
    val ld = datasourceLD.evaluator[Data].evaluate(ResourcePath.root() / ResourceName("testData") / ResourceName("lines.json"))
    val array = datasource.evaluator[Data].evaluate(ResourcePath.root() / ResourceName("testData") / ResourceName("array.json"))

    (ld |@| array).tupled.flatMap {
      case (readLD, readArray) => {
        val rd = gatherMultiple(readLD)
        val ra = gatherMultiple(readArray)

        (rd |@| ra) {
          case (lines, array) => {
            lines(0) must_= array(0)
            lines(1) must_= array(1)
          }
        }
      }
    }
  }

  "reading a non-existent file raises ResourceError.PathNotFound" >> {
    val bucket = Uri.uri("https://s3.amazonaws.com/slamdata-public-test")
    val ds = mkDatasource[G](S3JsonParsing.JsonArray, bucket, None)

    val path = ResourcePath.root() / ResourceName("does-not-exist")
    val read: Stream[G, Data] = Stream.force(ds.flatMap(_.evaluator[Data].evaluate(path)))

    read.compile.toList.value.unsafeRunSync must beLeft.like {
      case ResourceError.throwableP(ResourceError.PathNotFound(_)) => ok
    }
  }

  "list a file with special characters in it" >>* {
    OptionT(datasource.prefixedChildPaths(ResourcePath.root() / ResourceName("dir1")))
      .getOrElseF(IO.raiseError(new Exception(s"Failed to list resources under dir1")))
      .flatMap(gatherMultiple(_)).map { results =>
        results(0) must_== (ResourceName("dir2") -> ResourcePathType.prefix)
        results(1) must_== (ResourceName("fóóbar.ldjson") -> ResourcePathType.leafResource)
      }
  }

  def gatherMultiple[A](g: Stream[IO, A]) = g.compile.toList

  def mkDatasource[F[_]: Effect: MonadResourceErr](
    parsing: S3JsonParsing,
    bucket: Uri,
    creds: Option[S3Credentials])
      : F[Datasource[F, Stream[F, ?], ResourcePath]] =
    Http1Client[F]().map(client =>
      new S3DataSource[F](client, S3Config(bucket, parsing, creds)))

  val run = λ[IO ~> Id](_.unsafeRunSync)

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
