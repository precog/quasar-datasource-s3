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

import quasar.api.datasource.DatasourceError.InvalidConfiguration
import quasar.connector.ResourceError
import quasar.contrib.scalaz.MonadError_

import scala.concurrent.ExecutionContext.Implicits.global

import argonaut.Json
import cats.effect.IO
import org.specs2.mutable.Specification
import shims._

class S3DataSourceModuleSpec extends Specification {
  import S3DataSourceModuleSpec._

  "rejects invalid credentials" >> {
    // slamdata-private-test is a bucket that requires credentials to access
    val conf = Json.obj(
      "bucket" -> Json.jString("https://s3.amazonaws.com/slamdata-private-test"),
      "jsonParsing" -> Json.jString("array"))

    val ds = S3DataSourceModule.lightweightDatasource[IO](conf).unsafeRunSync.toEither

    ds must beLike {
      case Left(InvalidConfiguration(_, _, _)) => ok
    }
  }

  "rejects a non-bucket URI" >> {
    val conf = Json.obj(
      "bucket" -> Json.jString("https://example.com"),
      "jsonParsing" -> Json.jString("array"))

    val ds = S3DataSourceModule.lightweightDatasource[IO](conf).unsafeRunSync.toEither

    ds must beLike {
      case Left(InvalidConfiguration(_, _, _)) => ok
    }
  }
}

object S3DataSourceModuleSpec {
  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)
}
