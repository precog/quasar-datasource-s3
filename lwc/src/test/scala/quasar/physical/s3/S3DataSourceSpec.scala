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

import S3DataSourceSpec._
import cats.effect.IO
import fs2.Stream
import quasar.api.ResourceDiscoverySpec
import quasar.api.{ResourceName, ResourcePath}
import scalaz.{Foldable, Monoid, Id, ~>}, Id.Id
import shims._
import org.http4s.client.blaze.Http1Client
import org.http4s.Uri

final class S3DataSourceSpec extends ResourceDiscoverySpec[IO, Stream[IO, ?]] {
  val discovery = new S3DataSource[IO](
    Http1Client[IO]().unsafeRunSync,
    Uri.unsafeFromString("http://s3-lwc-test.s3.amazonaws.com"),
    S3JsonParsing.JsonArray)

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
