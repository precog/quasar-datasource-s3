/*
 * Copyright 2014–2019 SlamData Inc.
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

import slamdata.Predef.None

import scala.concurrent.ExecutionContext

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.Location
import org.http4s.syntax.kleisli._
import org.specs2.mutable.Specification

final class PreflightCheckSpec extends Specification {
  implicit val cs = IO.contextShift(ExecutionContext.global)

  val maxRedirects = 3
  val app = HttpRoutes.of[IO] {
    case Method.HEAD -> Root / "bucket0" / "" =>
      SeeOther(Location(Uri.uri("http://localhost/bucket1/")))
    case Method.HEAD -> Root / "bucket1" / "" =>
      MovedPermanently(Location(Uri.uri("http://localhost/bucket2/")))
    case Method.HEAD -> Root / "bucket2" / "" =>
      PermanentRedirect(Location(Uri.uri("http://localhost/bucket3/")))
    case Method.HEAD -> Root / "bucket3" / "" =>
      Ok()

    case Method.HEAD -> Root / "loop0" / "" =>
      PermanentRedirect(Location(Uri.uri("http://localhost/loop1/")))
    case Method.HEAD -> Root / "loop1" / "" =>
      PermanentRedirect(Location(Uri.uri("http://localhost/loop0/")))

    case Method.HEAD -> Root / "first" / "" =>
      PermanentRedirect(Location(Uri.uri("http://localhost/second/")))
    case Method.HEAD -> Root / "second" / ""  =>
      PermanentRedirect(Location(Uri.uri("http://localhost/third/")))
    case Method.HEAD -> Root / "third" / "" =>
      PermanentRedirect(Location(Uri.uri("http://localhost/fourth/")))
    case Method.HEAD -> Root / "fourth" / "" =>
      Ok()
  }.orNotFound

  val client = Client.fromHttpApp(app)
  val config = S3Config(Uri.uri("http://localhost/bucket1"), S3JsonParsing.LineDelimited, None, None)

  "updates bucket URI for permanent redirects" >> {
    val uri = Uri.uri("http://localhost/bucket2/")
    val redirectedTo = Uri.uri("http://localhost/bucket3/")

    impl.preflightCheck(client, uri, maxRedirects).unsafeRunSync must beSome(redirectedTo)
  }

  "bucket URI is not altered for non-permanent redirects" >> {
    val uri = Uri.uri("http://localhost/bucket0/")

    impl.preflightCheck(client, uri, maxRedirects).unsafeRunSync must beSome(uri)
  }

  "bucket URI is not altered for non-redirects" >> {
    val uri = Uri.uri("http://localhost/bucket3/")

    impl.preflightCheck(client, uri, maxRedirects).unsafeRunSync must beSome(uri)
  }

  "follows three permanent redirects" >> {
    val uri = Uri.uri("http://localhost/first/")
    val finalUri = Uri.uri("http://localhost/fourth/")

    impl.preflightCheck(client, uri, maxRedirects).unsafeRunSync must beSome(finalUri)
  }

  "fails with more than three redirects" >> {
    val uri = Uri.uri("http://localhost/loop0/")

    impl.preflightCheck(client, uri, maxRedirects).unsafeRunSync must beNone
  }
}
