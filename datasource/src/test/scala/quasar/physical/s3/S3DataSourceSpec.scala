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

import quasar.api.ResourceDiscoverySpec
import quasar.api.{ResourceName, ResourcePath, ResourcePathType}

import scala.concurrent.ExecutionContext.Implicits.global

import cats.effect.IO
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import scalaz.syntax.applicative._
import scalaz.{Foldable, Monoid, Id, ~>}, Id.Id
import shims._

import S3DataSourceSpec._

class S3DataSourceSpec extends ResourceDiscoverySpec[IO, Stream[IO, ?]] {
  "the root of a bucket with a trailing slash is not a resource" >>* {
    val root = ResourcePath.root() / ResourceName("")
    discovery.isResource(root).map(_ must beFalse)
  }

  "the root of a bucket is not a resource" >>* {
    val root = ResourcePath.root()
    discovery.isResource(root).map(_ must beFalse)
  }

  "a prefix without contents is not a resource" >>* {
    val path = ResourcePath.root() / ResourceName("prefix3") / ResourceName("subprefix5")
    discovery.isResource(path).map(_ must beFalse)
  }

  "list nested children" >>* {
    val path = ResourcePath.root() / ResourceName("dir1") / ResourceName("dir2") / ResourceName("dir3")

    val listing = discovery.children(path)

    listing.flatMap { list =>
      list.map(_.compile.toList)
        .getOrElse(IO.raiseError(new Exception("Could not list nested children under dir1/dir2/dir3")))
        .map {
          case List((resource, resourceType)) =>
            resource must_= ResourceName("flattenable.data")
            resourceType must_= ResourcePathType.resource
        }
    }
  }

  "list children at the root of the bucket" >>* {
    discovery.children(ResourcePath.root()).flatMap { list =>
      list.map(_.compile.toList).getOrElse(IO.raiseError(new Exception("Could not list children under the root")))
        .map(resources => {
          resources.length must_== 4
          resources(0) must_== (ResourceName("dir1") -> ResourcePathType.resourcePrefix)
          resources(1) must_== (ResourceName("extraSmallZips.data") -> ResourcePathType.resource)
          resources(2) must_== (ResourceName("prefix3") -> ResourcePathType.resourcePrefix)
          resources(3) must_== (ResourceName("testData") -> ResourcePathType.resourcePrefix)
        })
    }
  }

  "an actual file is a resource" >>* {
    val res = ResourcePath.root() / ResourceName("testData") / ResourceName("array.json")

    discovery.isResource(res) map (_ must beTrue)
  }

  "read line-delimited and array JSON" >>* {
    val ld = discoveryLD.evaluate(ResourcePath.root() / ResourceName("testData") / ResourceName("lines.json"))
    val array = discovery.evaluate(ResourcePath.root() / ResourceName("testData") / ResourceName("array.json"))

    (ld |@| array).tupled.flatMap {
      case (readLD, readArray) => {
        val rd = readLD.map(_.compile.toList)
          .toOption.getOrElse(IO.raiseError(new Exception("Could not read lines.json")))
        val ra = readArray.map(_.compile.toList)
          .toOption.getOrElse(IO.raiseError(new Exception("Could not read array.json")))

        (rd |@| ra) {
          case (lines, array) => {
            lines(0) must_= array(0)
            lines(1) must_= array(1)
          }
        }
      }
    }
  }

  val discoveryLD = new S3DataSource[IO, IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-public-test"),
      S3JsonParsing.LineDelimited,
      None), Map.empty)

  val discovery = new S3DataSource[IO, IO](
    Http1Client[IO]().unsafeRunSync,
    S3Config(
      Uri.uri("https://s3.amazonaws.com/slamdata-public-test"),
      S3JsonParsing.JsonArray,
      None), Map.empty)

  val nonExistentPath =
    ResourcePath.root() / ResourceName("does") / ResourceName("not") / ResourceName("exist")

  val run = λ[IO ~> Id](_.unsafeRunSync)
}

object S3DataSourceSpec {
  implicit val unsafeStreamFoldable: Foldable[Stream[IO, ?]] =
    new Foldable[Stream[IO, ?]] with Foldable.FromFoldMap[Stream[IO, ?]] {
      def foldMap[A, M](fa: Stream[IO, A])(f: A => M)(implicit M: Monoid[M]) =
        fa.compile.fold(M.zero)((m, a) => M.append(m, f(a))).unsafeRunSync
    }
}
