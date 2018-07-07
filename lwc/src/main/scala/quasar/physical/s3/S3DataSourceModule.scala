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


import quasar.Data
import quasar.api.{DataSourceType, ResourcePath}
import quasar.connector.{DataSource, LightweightDataSourceModule}
import quasar.api.DataSourceError.InitializationError

import argonaut.Json
import cats.effect.{Timer, ConcurrentEffect}
import eu.timepit.refined.auto._
import fs2.Stream
import scalaz.\/
// import scalaz.syntax.either._
// import scalaz.syntax.applicative._
import slamdata.Predef.{Stream => _, _}
// import shims._

object S3DataSourceModule extends LightweightDataSourceModule {
  def kind: DataSourceType = DataSourceType("remote", 1L)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def lightweightDataSource[
      F[_]: ConcurrentEffect: Timer,
      G[_]: ConcurrentEffect: Timer](
      config: Json)
      : F[InitializationError[Json] \/ DataSource[F, Stream[G, ?], ResourcePath, Stream[G, Data]]] = {
    config.as[S3Config].result match {
      case Right(s3Config) => {
        // val ds: DataSource[F, Stream[F, ?], ResourcePath, Stream[F, Data]] =
        //   new S3DataSource[F](???, s3Config.bucket, s3Config.parsing)

        // ds.right[InitializationError[Json]].point[F]
        ???
      }

      case Left((msg, history)) => slamdata.Predef.???
    }
  }
}
