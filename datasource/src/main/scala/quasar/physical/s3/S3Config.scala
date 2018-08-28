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
import argonaut.{DecodeJson, DecodeResult, EncodeJson, Json}
import org.http4s.Uri
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.instances.option._
import scalaz.syntax.show._
import slamdata.Predef._
import shims._

final case class S3Config(bucket: Uri, parsing: S3JsonParsing, credentials: Option[S3Credentials])

final case class AccessKey(value: String)
final case class SecretKey(value: String)
final case class Region(name: String)

final case class S3Credentials(accessKey: AccessKey, secretKey: SecretKey, region: Region)

object S3Config {
  /*  Example configuration for public buckets with line-delimited JSON:
   *  {
   *    "bucket": "<uri to bucket>",
   *    "jsonParsing": "lineDelimited"
   *  }
   *
   *  Example configuration for public buckets with array JSON:
   *  {
   *    "bucket": "<uri to bucket>",
   *    "jsonParsing": "array"
   *  }
   *
   *  Example configuration for a secure bucket with array JSON:
   *  {
   *    "bucket":"https://some.bucket.uri",
   *    "jsonParsing":"array",
   *    "credentials": {
   *      "accessKey":"some access key",
   *      "secretKey":"super secret key",
   *      "region":"us-east-1"
   *    }
   *  }
   *
   *  Example configuration for a secure bucket with line-delimited JSON:
   *  {
   *    "bucket":"https://some.bucket.uri",
   *    "jsonParsing":"lineDelimited",
   *    "credentials": {
   *      "accessKey":"some access key",
   *      "secretKey":"super secret key",
   *      "region":"us-east-1"
   *    }
   *  }
   *
   */
  private val parseStrings =
    Map[String, S3JsonParsing](
      "array" -> S3JsonParsing.JsonArray,
      "lineDelimited" -> S3JsonParsing.LineDelimited)

  private val failureMsg =
    "Failed to parse configuration for S3 connector."

  implicit val decodeJson: DecodeJson[S3Config] =
    DecodeJson { c =>
      val b = c.get[String]("bucket").toOption >>= (Uri.fromString(_).toOption)
      val jp = c.get[String]("jsonParsing").toOption >>= (parseStrings.get(_))

      (c.downField("credentials").success, b, jp) match {
        case (Some(_), Some(bk), Some(p)) => {
          val creds = DecodeJson.of[S3Credentials].decode(c)

          creds.toOption match {
            case Some(creds0) => DecodeResult.ok(S3Config(bk, p, Some(creds0)))
            case None => DecodeResult.fail(creds.message.getOrElse(failureMsg), c.history)
          }
        }

        case (None, Some(bk), Some(p)) =>
          DecodeResult.ok(S3Config(bk, p, None))

        case _ =>
          DecodeResult.fail(failureMsg, c.history)
      }
    }

  implicit val encodeJson: EncodeJson[S3Config] =
    EncodeJson { config =>
      Json.obj(
        "bucket" -> Json.jString(config.bucket.renderString),
        "jsonParsing" -> Json.jString(config.parsing.shows))
    }
}

object S3Credentials {
  private val incompleteCredsMsg =
    "The 'credentials' key must include 'accessKey', 'secretKey', and 'region'"

  implicit val decodeJson: DecodeJson[S3Credentials] =
    DecodeJson { c =>
      val creds = c.downField("credentials")
      val akey = creds.get[String]("accessKey").map(AccessKey(_)).toOption
      val skey = creds.get[String]("secretKey").map(SecretKey(_)).toOption
      val rkey = creds.get[String]("region").map(Region(_)).toOption

      (akey, skey, rkey).mapN(S3Credentials(_, _, _)) match {
        case Some(creds0) => DecodeResult.ok(creds0)
        case None => DecodeResult.fail(incompleteCredsMsg, c.history)
      }
    }

  implicit val encodeJson: EncodeJson[S3Credentials] =
    EncodeJson { creds =>
      Json.obj(
        "accessKey" -> Json.jString(creds.accessKey.value),
        "secretKey" -> Json.jString(creds.secretKey.value),
        "region"    -> Json.jString(creds.region.name))
    }

}
