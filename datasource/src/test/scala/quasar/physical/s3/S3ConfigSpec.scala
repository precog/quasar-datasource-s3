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

import slamdata.Predef._
import quasar.connector.DataFormat, DataFormat._

import org.specs2.mutable.Specification
import argonaut.{Json, DecodeJson}

class S3ConfigSpec extends Specification {
  val decode = DecodeJson.of[S3Config].decodeJson(_)

  "fails when the credentials key exists but it's incomplete" >> {
    val incompleteCreds = Json.obj(
      "bucket" -> Json.jString("https://some.bucket.uri"),
      "jsonParsing" -> Json.jString("array"),
      "credentials" -> Json.obj(
        "accessKey" -> Json.jString("some access key"),
        "secretKey" -> Json.jString("super secret key")))

    decode(incompleteCreds).toEither must beLeft
  }

  "reads configuration for secure buckets" >> {
    val conf = Json.obj(
      "bucket" -> Json.jString("https://some.bucket.uri"),
      "jsonParsing" -> Json.jString("array"),
      "credentials" -> Json.obj(
        "accessKey" -> Json.jString("some access key"),
        "secretKey" -> Json.jString("super secret key"),
        "region" -> Json.jString("us-east-1")))

    decode(conf).toEither must beRight((c: S3Config) => c.credentials must beSome)
  }

  "reads configuration for public buckets" >> {
    val conf = Json.obj(
      "bucket" -> Json.jString("https://some.bucket.uri"),
      "jsonParsing" -> Json.jString("array"))

    decode(conf).toEither must beRight((c: S3Config) => c.credentials must beNone)
  }

  "parsable type" >> {
    "precise json" >> {
      val conf = Json.obj(
        "bucket" -> Json.jString("https://some.bucket.uri"),
        "format" -> Json.obj(
          "type" -> Json.jString("json"),
          "precise" -> Json.jBool(true),
          "variant" -> Json.jString("line-delimited")))
      decode(conf).toEither must beRight((c: S3Config) => c.format === DataFormat.precise(DataFormat.ldjson))
    }
  }
}
