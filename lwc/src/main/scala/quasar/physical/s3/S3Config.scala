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
import argonaut.{DecodeJson, DecodeResult}
import org.http4s.Uri
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.instances.option._
import slamdata.Predef._

final case class S3Config(bucket: Uri, parsing: S3JsonParsing, credentials: Option[S3Credentials])
final case class S3Credentials(accessKey: String, secretKey: String, region: String)

object S3Config {
  private val parseStrings =
    Map[String, S3JsonParsing](
      "array" -> S3JsonParsing.JsonArray,
      "lineDelimited" -> S3JsonParsing.LineDelimited)

  private val failureMessage = "Failed to parse configuration for S3 connector."

  implicit val decodeJson: DecodeJson[S3Config] =
    DecodeJson { c =>
      val b = c.get[String]("bucket").toOption >>= (Uri.fromString(_).toOption)
      val jp = c.get[String]("jsonParsing").toOption >>= (parseStrings.get(_))
      val akey = c.downField("credentials").get[String]("accessKey").toOption
      val skey = c.downField("credentials").get[String]("secretKey").toOption
      val rkey = c.downField("credentials").get[String]("region").toOption

      (b, jp).mapN {
        case (u, p) => {
          (akey, skey, rkey).mapN(S3Credentials(_, _, _)) match {
            case Some(creds) => S3Config(u, p, Some(creds))
            case None => S3Config(u, p, None)
          }
        }
      } match {
        case Some(config) => DecodeResult.ok(config)
        case None => DecodeResult.fail(failureMessage, c.history)
      }
    }
}
