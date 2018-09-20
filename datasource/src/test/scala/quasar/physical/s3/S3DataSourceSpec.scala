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
import quasar.connector.DatasourceSpec
import quasar.connector.ResourceError
import quasar.contrib.scalaz.MonadError_

import scala.concurrent.ExecutionContext.Implicits.global

import cats.effect.IO
import cats.data.OptionT
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import scalaz.{Id, ~>}, Id.Id
import shims._

import S3DataSourceSpec._

class S3DataSourceSpec extends DatasourceSpec[IO, Stream[IO, ?]] {
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

    "list a file with special characters in it" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("dir1"),
        List(
          ResourceName("dir2") -> ResourcePathType.prefix,
          ResourceName("fóóbar.ldjson") -> ResourcePathType.leafResource))
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
  }

  def assertEvaluate(ds: S3DataSource[IO], path: ResourcePath, expected: List[Data]) =
    ds.evaluator[Data].evaluate(path).flatMap { res =>
      res.compile.toList.map { _ must_== expected }
    }

  def assertPrefixedChildPaths(path: ResourcePath, expected: List[(ResourceName, ResourcePathType)]) =
    OptionT(datasource.prefixedChildPaths(path))
      .getOrElseF(IO.raiseError(new Exception(s"Failed to list resources under $path")))
      .flatMap(_.compile.toList).map { _ must_== expected }

  def gatherMultiple[A](g: Stream[IO, A]) = g.compile.toList

  val data_12_34 = List(Data.Arr(List(Data.Int(1), Data.Int(2))), Data.Arr(List(Data.Int(3), Data.Int(4))))

  val datasourceLD = new S3DataSource[IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-public-test"),
      S3JsonParsing.LineDelimited,
      None))

  val datasource = new S3DataSource[IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-public-test"),
      S3JsonParsing.JsonArray,
      None))

  val nonExistentPath =
    ResourcePath.root() / ResourceName("does") / ResourceName("not") / ResourceName("exist")

  val run = λ[IO ~> Id](_.unsafeRunSync)
}

object S3DataSourceSpec {
  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)
}
