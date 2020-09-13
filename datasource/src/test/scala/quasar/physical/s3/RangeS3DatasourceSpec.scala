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

import scala.{Stream => _, _}, Predef._

//import quasar.api.datasource.DatasourceError._
//import quasar.connector.{ByteStore, ResourceError}
//import quasar.contrib.pathy.AFile

//import pathy.Path
import pathy.Path.{Sandboxed, file}

//import argonaut.Argonaut //, Argonaut._
import java.nio.charset.StandardCharsets
import cats.effect.IO
//import java.util.UUID
import cats.effect.testing.specs2.CatsIO
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.`Content-Range`
import org.http4s.dsl._
import org.http4s.implicits._

import scala.concurrent.ExecutionContext

//import argonaut.Argonaut //, Argonaut._
import cats.effect.{ContextShift, IO, Timer}
//import cats.kernel.instances.uuid._
import org.specs2.mutable.Specification
//import java.util.UUID
import shims._

import org.specs2.mutable.Specification
//import quasar.CIString

object RangeS3DatasourceSpec extends Specification 
    with CatsIO 
    with Http4sDsl[IO]
    with Http4sClientDsl[IO] {

    import S3DatasourceModuleSpec._

    implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
    implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
    implicit val ec: ExecutionContext = ExecutionContext.global

    val routes = HttpRoutes.of[IO] {
        case GET -> Root / "error" => IO(
          Response(
            Status.Ok,
            body = Stream.emits[IO, Byte]("abcd".getBytes(StandardCharsets.UTF_8)) ++ Stream.raiseError[IO](new Throwable("error"))))
        case req @ GET -> Root / "ok" => IO(
          Response(
            Status.Ok,
            body = Stream.emits("efgh".getBytes(StandardCharsets.UTF_8))))
        case GET -> Root / "cancel" => IO(
          Response(
            Status.Ok,
            body = Stream.emits("ijkl".getBytes(StandardCharsets.UTF_8)) ++  Stream.raiseError[IO](new Throwable("canceled request"))))
    }

    def client: Client[IO] = {
      Client.fromHttpApp(routes.orNotFound)
    }

    val testBucket = Uri.uri("https://example.com")
    val root = pathy.Path.rootDir[Sandboxed]

    "succeed when exit case is completed" >> {
      for {
        c <- impl.evaluate(client, testBucket, root </> file("ok"))
      } yield {
        c.through(fs2.text.utf8Decode).compile.toList.unsafeRunSync must_=== List("efgh")
      }
    }

    "succeed when exit case is error" >> {
      for {
        c <- impl.evaluate(client, testBucket, root </> file("error"))
      } yield {
        c.through(fs2.text.utf8Decode).compile.toList.unsafeRunSync must_=== List("abcd")
      }
    }

    "error when exit case is canceled" >> {
      for {
        c: Stream[IO, Byte] <- impl.evaluate(client, testBucket, root </> file("cancel"))
      } yield {
        c.through(fs2.text.utf8Decode).compile.toList.unsafeRunSync must_=== List("a")
      }
    }

}