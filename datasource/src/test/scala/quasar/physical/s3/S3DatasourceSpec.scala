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

import quasar.ScalarStages
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.common.data.Data
import quasar.connector._
import quasar.connector.datasource.{DatasourceSpec, LightweightDatasourceModule}
import quasar.contrib.scalaz.MonadError_
import quasar.qscript.InterpretedRead

import java.nio.charset.Charset
import scala.concurrent.ExecutionContext

import cats.data.OptionT
import cats.effect.{IO, Resource}
import cats.syntax.applicative._

import fs2.Stream

import org.http4s.Uri

import shims.applicativeToScalaz

import S3DatasourceSpec._

class S3DatasourceSpec extends DatasourceSpec[IO, Stream[IO, ?], ResourcePathType.Physical] {

  def iRead[A](path: A): InterpretedRead[A] = InterpretedRead(path, ScalarStages.Id)

  val testBucket = Uri.uri("https://slamdata-public-test.s3.amazonaws.com")
  val nonExistentPath =
    ResourcePath.root() / ResourceName("does") / ResourceName("not") / ResourceName("exist")

  val spanishResourceName1 = ResourceName("El veloz murciélago hindú")
  val spanishResourcePrefix = ResourcePath.root() / ResourceName("testData") / spanishResourceName1 / ResourceName("comía feliz cardillo y kiwi") / ResourceName("La cigüeña tocaba el saxofón")
  val spanishResourceLeaf = ResourceName("detrás del palenque de paja")
  val spanishResource = spanishResourcePrefix / spanishResourceLeaf

  "pathIsResource" >> {
    "the root of a bucket is not a resource" >>* {
      val root = ResourcePath.root()
      datasource.flatMap(_.pathIsResource(root)).use(b => IO.pure(b must beFalse))
    }

    "a prefix without contents is not a resource" >>* {
      val path = ResourcePath.root() / ResourceName("prefix3") / ResourceName("subprefix5")
      datasource.flatMap(_.pathIsResource(path)).use(b => IO.pure(b must beFalse))
    }

    // this also tests request signing for secured buckets
    "a non-existing file with special chars is not a resource" >>* {
      val res = ResourcePath.root() / ResourceName("testData") / ResourceName("""-_.!~*'() /"\#$%^&<>,?[]+=:;`""")
      datasource.flatMap(_.pathIsResource(res)).use(b => IO.pure(b must beFalse))
    }

    "an actual file is a resource" >>* {
      val res = ResourcePath.root() / ResourceName("testData") / ResourceName("array.json")
      datasource.flatMap(_.pathIsResource(res)).use(b => IO.pure(b must beTrue))
    }

    "an actual file with special chars in path is a resource" >>* {
      val res = ResourcePath.root() / ResourceName("testData") / ResourceName("á") / ResourceName("βç.json")
      datasource.flatMap(_.pathIsResource(res)).use(b => IO.pure(b must beTrue))
    }

    "an actual file with special chars in deeper path is a resource" >>* {
      datasource.flatMap(_.pathIsResource(spanishResource)).use(b => IO.pure(b must beTrue))
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
          ResourceName("extraSmallZips.data") -> ResourcePathType.leafResource,
          ResourceName("dir1") -> ResourcePathType.prefix,
          ResourceName("prefix3") -> ResourcePathType.prefix,
          ResourceName("testData") -> ResourcePathType.prefix))
    }

    "list children with special chars" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("dir1"),
        List(
          ResourceName("dir2") -> ResourcePathType.prefix,
          ResourceName("fóóbar.ldjson") -> ResourcePathType.leafResource))
    }

    "list children with more special chars" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("testData"),
        List(
          spanishResourceName1 -> ResourcePathType.prefix,
          ResourceName("a b") -> ResourcePathType.prefix,
          ResourceName("array.json") -> ResourcePathType.leafResource,
          ResourceName("lines.json") -> ResourcePathType.leafResource,
          ResourceName("test.csv") -> ResourcePathType.leafResource,
          ResourceName("á") -> ResourcePathType.prefix))
    }

    "list children when space in path" >>* {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("testData") / ResourceName("a b"),
        List(
          ResourceName("a b.json") -> ResourcePathType.leafResource))
    }

    "list children with special chars when special chars in path" >>* {
      assertPrefixedChildPaths(
        spanishResourcePrefix,
        List(spanishResourceLeaf -> ResourcePathType.leafResource))
    }
  }

  "evaluate" >> {
    "read line-delimited JSON" >>* {
      assertResultBytes(
        datasourceLD,
        ResourcePath.root() / ResourceName("testData") / ResourceName("lines.json"),
        "[1, 2]\n[3, 4]\n".getBytes(Charset.forName("UTF-8")))
    }

    "read array JSON" >>* {
      assertResultBytes(
        datasource,
        ResourcePath.root() / ResourceName("testData") / ResourceName("array.json"),
        "[[1, 2], [3, 4]]\n".getBytes(Charset.forName("UTF-8")))
    }

    "read CSV" >>* {
      val expected = "foo,bar\r\n1,2"
      assertResultBytes(
        datasourceCSV,
        ResourcePath.root() / ResourceName("testData") / ResourceName("test.csv"),
        expected.getBytes(Charset.forName("UTF-8")))
    }

    "read array JSON of resource with special chars in path" >>* {
      assertResultBytes(
        datasource,
        ResourcePath.root() / ResourceName("testData") / ResourceName("á") / ResourceName("βç.json"),
        "[[1, 2], [3, 4]]\n".getBytes(Charset.forName("UTF-8")))
    }

    "read line-delimited JSON with special chars of resource with special chars in path" >>* {
      val esStr = "\"El veloz murciélago hindú comía feliz cardillo y kiwi. La cigüeña tocaba el saxofón detrás del palenque de paja.\"\n"

      assertResultBytes(
        datasourceLD,
        spanishResource,
        esStr.getBytes(Charset.forName("UTF-8")))
    }

    "reading a non-existent file raises ResourceError.PathNotFound" >>* {
      val creds = Resource.liftF(credentials)
      val ds = creds.flatMap(c => mkDatasource(S3Config(testBucket, DataFormat.json, c)))

      val path = ResourcePath.root() / ResourceName("does-not-exist")
      val read = ds.flatMap(_.loadFull(iRead(path)).value)

      MonadResourceErr[IO].attempt(read.use(_ => IO.unit)).map(_.toEither must beLeft.like {
        case ResourceError.PathNotFound(_) => ok
      })
    }
  }

  def assertResultBytes(
      ds: Resource[IO, LightweightDatasourceModule.DS[IO]],
      path: ResourcePath,
      expected: Array[Byte]) =
    ds.flatMap(_.loadFull(iRead(path)).value) use {
      case Some(QueryResult.Typed(_, data, ScalarStages.Id)) =>
        data.compile.to(Array).map(_ must_=== expected)

      case _ =>
        IO(ko("Unexpected QueryResult"))
    }

  def assertPrefixedChildPaths(path: ResourcePath, expected: List[(ResourceName, ResourcePathType)]) =
    OptionT(datasource.flatMap(_.prefixedChildPaths(path)))
      .getOrElseF(Resource.liftF(IO.raiseError(new Exception(s"Failed to list resources under $path"))))
      .use(gatherMultiple(_))
      .map(result => {
        // assert the same elements, with no duplicates
        result.length must_== expected.length
        result.toSet must_== expected.toSet
      })

  def gatherMultiple[A](g: Stream[IO, A]) = g.compile.toList

  val data_12_34 = List(Data.Arr(List(Data.Int(1), Data.Int(2))), Data.Arr(List(Data.Int(3), Data.Int(4))))

  def credentials: IO[Option[S3Credentials]] = None.pure[IO]

  def mkDatasource(config: S3Config)
      : Resource[IO, LightweightDatasourceModule.DS[IO]] = {

    import ExecutionContext.Implicits.global

    AsyncHttpClientBuilder[IO]
      .map(AwsV4Signing(config))
      .map(S3Datasource(_, config))
  }

  val datasource = mkDatasource(S3Config(testBucket, DataFormat.json, None))
  val datasourceLD = mkDatasource(S3Config(testBucket, DataFormat.ldjson, None))
  val datasourceCSV = mkDatasource(S3Config(testBucket, DataFormat.SeparatedValues.Default, None))
}

object S3DatasourceSpec {
  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)
}
