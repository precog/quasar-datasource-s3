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

import scala.concurrent.ExecutionContext.Implicits.global

import cats.effect.IO
import cats.data.OptionT
import cats.syntax.applicative._
import org.http4s.{Uri, Request}
import org.http4s.client.blaze.Http1Client
import org.specs2.mutable.Specification
import pathy.Path

final class ChildrenSpec extends Specification {
  "lists all resources at the root of the bucket, one per request" >> {
    val client = Http1Client[IO]()
    // Force S3 to return a single element per page in ListObjects,
    // to ensure pagination works correctly
    val bucket = Uri.uri("https://s3.amazonaws.com/slamdata-public-test/").withQueryParam("max-keys", "1")
    val dir = Path.rootDir
    val sign: Request[IO] => IO[Request[IO]] = _.pure[IO]

    OptionT(client.flatMap(impl.children(_, bucket, dir, sign)))
      .getOrElseF(IO.raiseError(new Exception("Could not list children under the root")))
      .flatMap(_.compile.toList).map { children =>
        children.length must_== 4
        children(0).toEither must_== Left(Path.DirName("dir1"))
        children(1).toEither must_== Right(Path.FileName("extraSmallZips.data"))
        children(2).toEither must_== Left(Path.DirName("prefix3"))
        children(3).toEither must_== Left(Path.DirName("testData"))
      }.unsafeRunSync
  }
}
