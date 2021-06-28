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

import quasar.{RateLimiting, RateLimiter}
import quasar.api.datasource.DatasourceError._
import quasar.connector.{ByteStore, ResourceError}
import quasar.connector.datasource.Reconfiguration
import quasar.contrib.scalaz.MonadError_

import scala.concurrent.ExecutionContext

import argonaut.{Argonaut, Json}, Argonaut._
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.kernel.instances.uuid._
import org.specs2.mutable.Specification
import java.util.UUID
import scalaz.NonEmptyList
import shims._

class S3DatasourceModuleSpec extends Specification {
  import S3DatasourceModuleSpec._

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val ec: ExecutionContext = ExecutionContext.global

  val rateLimiting: Resource[IO, RateLimiting[IO, UUID]] =
    RateLimiter[IO, UUID](IO.delay(UUID.randomUUID()))

  "rejects invalid credentials" >> {
    // slamdata-private-test is a bucket that requires credentials to access
    val conf = Json.obj(
      "bucket" -> Json.jString("https://slamdata-private-test.s3.amazonaws.com"),
      "jsonParsing" -> Json.jString("array"))

    rateLimiting.flatMap((rl: RateLimiting[IO, UUID]) =>
      S3DatasourceModule.datasource[IO, UUID](conf, rl, ByteStore.void[IO], _ => IO(None)))
        .use(ds => IO(ds must beLike {
          case Left(AccessDenied(_, _, _)) => ok
        }))
        .unsafeRunSync()
  }

  "rejects a non-bucket URI" >> {
    val conf = Json.obj(
      "bucket" -> Json.jString("https://google.com"),
      "jsonParsing" -> Json.jString("array"))

    rateLimiting.flatMap((rl: RateLimiting[IO, UUID]) =>
      S3DatasourceModule.datasource[IO, UUID](conf, rl, ByteStore.void[IO], _ => IO(None)))
        .use(ds => IO(ds must beLike {
          case Left(AccessDenied(_, _, _)) => ok
        }))
        .unsafeRunSync()
  }

  "migration" in {
    "migrate config as itself" >> {
      val config = Json(
        "bucket" := Json.jString("www.quux.com"),
        "format" := Json.obj(
          "type" := Json.jString("json"),
          "variant" := Json.jString("line-delimited"),
          "precise" := Json.jBool(false)),
        "credentials" := Json(
          "accessKey" := Json.jString("aa"),
          "secretKey" := Json.jString("ss"),
          "region" := Json.jString("rr")))

      S3DatasourceModule.migrateConfig[IO](1, 1, config).unsafeRunSync() must beRight(config)
    }

    "fail to migrate malformed config" >> {
      val malformed = "malformed".asJson

      val error = MalformedConfiguration(
        S3DatasourceModule.kind,
        malformed,
        "Configuration to migrate is malformed.")

      S3DatasourceModule.migrateConfig[IO](1, 1, malformed).unsafeRunSync() must beLeft(error)
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
        "format" -> Json.obj(
          "type" -> Json.jString("json"),
          "variant" -> Json.jString("array-wrapped"),
          "precise" -> Json.jBool(false)),
        "credentials" -> Json.obj(
          "accessKey" -> Json.jString("<REDACTED>"),
          "secretKey" -> Json.jString("<REDACTED>"),
          "region" -> Json.jString("<REDACTED>")))

      S3DatasourceModule.sanitizeConfig(conf) must_== redactedConf
    }

    "only migrate when there are no credentials to redact" >> {
      val conf = Json.obj(
        "bucket" -> Json.jString("https://some.bucket.uri"),
        "jsonParsing" -> Json.jString("array"))

      val migrated = Json.obj(
        "bucket" -> Json.jString("https://some.bucket.uri"),
        "credentials" -> Json.jNull,
        "format" -> Json.obj(
          "type" -> Json.jString("json"),
          "variant" -> Json.jString("array-wrapped"),
          "precise" -> Json.jBool(false)))

      S3DatasourceModule.sanitizeConfig(conf) must_== migrated
    }
  }

  "reconfiguration" >> {
    val patchJson = Json(
      "bucket" := Json.jString("www.foo.bar"),
      "format" := Json.obj(
        "type" := Json.jString("json"),
        "variant" := Json.jString("array-wrapped"),
        "precise" := Json.jBool(false)))
    val sourceJson = Json(
      "bucket" := Json.jString("www.bar.baz"),
      "format" := Json.obj(
        "type" := Json.jString("json"),
        "variant" := Json.jString("line-delimited"),
        "precise" := Json.jBool(false)),
      "credentials" := Json(
        "accessKey" := Json.jString("a"),
        "secretKey" := Json.jString("s"),
        "region" := Json.jString("r")))

    "returns malformed error if patch or source can't be decoded" >> {
      val incorrect = Json()

      "both" >> {
        S3DatasourceModule.reconfigure(incorrect, incorrect) must beLeft(
          MalformedConfiguration(
            S3DatasourceModule.kind,
            incorrect,
            "Source configuration in reconfiguration is malformed."))
      }
      "source" >> {
        S3DatasourceModule.reconfigure(incorrect, patchJson) must beLeft(
          MalformedConfiguration(
            S3DatasourceModule.kind,
            incorrect,
            "Source configuration in reconfiguration is malformed."))
      }
      "patch" >> {
        S3DatasourceModule.reconfigure(sourceJson, incorrect) must beLeft(
          MalformedConfiguration(
            S3DatasourceModule.kind,
            incorrect,
            "Patch configuration in reconfiguration is malformed."))
      }
    }
    "reconfigures non-sensitive fields" >> {
      val expected = Json(
        "bucket" := Json.jString("www.foo.bar"),
        "format" := Json.obj(
          "type" := Json.jString("json"),
          "variant" := Json.jString("array-wrapped"),
          "precise" := Json.jBool(false)),
        "credentials" := Json(
          "accessKey" := Json.jString("a"),
          "secretKey" := Json.jString("s"),
          "region" := Json.jString("r")))
      S3DatasourceModule.reconfigure(sourceJson, patchJson) must beRight((Reconfiguration.Reset, expected))
    }

    "returns invalid configuration error if patch has sensitive information" >> {
      val sensitivePatch = Json(
        "bucket" := Json.jString("www.quux.com"),
        "format" := Json.obj(
          "type" := Json.jString("json"),
          "variant" := Json.jString("line-delimited"),
          "precise" := Json.jBool(false)),
        "credentials" := Json(
          "accessKey" := Json.jString("aa"),
          "secretKey" := Json.jString("ss"),
          "region" := Json.jString("rr")))
      val expected = Json(
        "bucket" := Json.jString("www.quux.com"),
        "format" := Json.obj(
          "type" := Json.jString("json"),
          "variant" := Json.jString("line-delimited"),
          "precise" := Json.jBool(false)),
        "credentials" := Json(
          "accessKey" := Json.jString("<REDACTED>"),
          "secretKey" := Json.jString("<REDACTED>"),
          "region" := Json.jString("<REDACTED>")))

      S3DatasourceModule.reconfigure(sourceJson, sensitivePatch) must beLeft(
        InvalidConfiguration(
          S3DatasourceModule.kind,
          expected,
          NonEmptyList("Patch configuration contains sensitive information.")))
    }
  }
}

object S3DatasourceModuleSpec {
  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)
}
