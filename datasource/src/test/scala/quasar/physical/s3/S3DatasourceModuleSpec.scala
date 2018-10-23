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

import quasar.api.datasource.DatasourceError.AccessDenied
import quasar.connector.ResourceError
import quasar.contrib.scalaz.MonadError_

import scala.concurrent.ExecutionContext

import argonaut.Json
import cats.effect.{ContextShift, IO, Timer}
import org.specs2.mutable.Specification
import shims._

class S3DatasourceModuleSpec extends Specification {
  import S3DatasourceModuleSpec._

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val ec: ExecutionContext = ExecutionContext.global

  "rejects invalid credentials" >> {
    // slamdata-private-test is a bucket that requires credentials to access
    val conf = Json.obj(
      "bucket" -> Json.jString("https://s3.amazonaws.com/slamdata-private-test"),
      "jsonParsing" -> Json.jString("array"))

    val ds = S3DatasourceModule.lightweightDatasource[IO](conf).unsafeRunSync.toEither

    ds must beLike {
      case Left(AccessDenied(_, _, _)) => ok
    }
  }

  "rejects a non-bucket URI" >> {
    val conf = Json.obj(
      "bucket" -> Json.jString("https://example.com"),
      "jsonParsing" -> Json.jString("array"))

    val ds = S3DatasourceModule.lightweightDatasource[IO](conf).unsafeRunSync.toEither

    ds must beLike {
      case Left(AccessDenied(_, _, _)) => ok
    }
  }

  "sanitizeConfig" in {
    "removes AccessKey, SecretKey and Region from credentials" >> {
      val conf = Json.obj(
        "bucket" -> Json.jString("https://some.bucket.uri"),
        "jsonParsing" -> Json.jString("array"),
        "credentials" -> Json.obj(
          "accessKey" -> Json.jString("some access key"),
          "secretKey" -> Json.jString("super secret key"),
          "region" -> Json.jString("us-east-1")))

      val redactedConf = Json.obj(
        "bucket" -> Json.jString("https://some.bucket.uri"),
        "jsonParsing" -> Json.jString("array"),
        "credentials" -> Json.obj(
          "accessKey" -> Json.jString("<REDACTED>"),
          "secretKey" -> Json.jString("<REDACTED>"),
          "region" -> Json.jString("<REDACTED>")))

      S3DatasourceModule.sanitizeConfig(conf) must_== redactedConf
    }

    "does nothing when there are no credentials to redact" >> {
      val conf = Json.obj(
        "bucket" -> Json.jString("https://some.bucket.uri"),
        "jsonParsing" -> Json.jString("array"))

      S3DatasourceModule.sanitizeConfig(conf) must_== conf
    }
  }
}

object S3DatasourceModuleSpec {
  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)
}
