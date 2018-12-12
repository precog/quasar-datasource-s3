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
import quasar.connector.CompressionScheme

import argonaut._ , Argonaut._
import org.http4s.Uri
import monocle.Prism
import scalaz.syntax.show._
import shims._

final case class S3Config(
    bucket: Uri,
    parsing: S3JsonParsing,
    compressionScheme: Option[CompressionScheme],
    credentials: Option[S3Credentials])

final case class AccessKey(value: String)
final case class SecretKey(value: String)
final case class Region(name: String)

final case class S3Credentials(accessKey: AccessKey, secretKey: SecretKey, region: Region)

object S3Config {

  private val parseStrings =
    Map[String, S3JsonParsing](
      "array" -> S3JsonParsing.JsonArray,
      "lineDelimited" -> S3JsonParsing.LineDelimited)

  private val compressionSchemePrism: Prism[String, CompressionScheme] =
    Prism.partial[String, CompressionScheme] {
      case "gzip" => CompressionScheme.Gzip
    } {
      case CompressionScheme.Gzip  => "gzip"
    }

  private val failureMsg =
    "Failed to parse configuration for S3 connector."


  implicit val uriCodec: CodecJson[Uri] = CodecJson(
    u => Json.jString(u.renderString),
    optionDecoder(_.as[String].toOption.flatMap(Uri.fromString(_).toOption), "Uri").decode(_))

  implicit val jsonParsingCodec: CodecJson[S3JsonParsing] = CodecJson[S3JsonParsing](
    p => Json.jString(p.shows),
    optionDecoder(_.as[String].toOption.flatMap(s => parseStrings.get(s)), "jsonParsing").decode
  ).setName("Unrecognized jsonParsing field")

  implicit val compressionSchemeCodec: CodecJson[CompressionScheme] = CodecJson[CompressionScheme](
    { cs => Json.jString(compressionSchemePrism(cs)) },
    { optionDecoder(_.as[String].toOption.flatMap(s => compressionSchemePrism.getOption(s)), "compressionScheme").decode }
  ).setName("Unrecognized compression scheme")

  implicit val configCodec: CodecJson[S3Config] =
    casecodec4(
      S3Config.apply, S3Config.unapply)(
      "bucket", "jsonParsing", "compressionScheme", "credentials")
      .setName(failureMsg)

}

object S3Credentials {
  implicit val accessKeyCodec: CodecJson[AccessKey] =
    CodecJson(_.value.asJson, jdecode1(AccessKey(_)).decode)

  implicit val secretKeyCodec: CodecJson[SecretKey] =
    CodecJson(_.value.asJson, jdecode1(SecretKey(_)).decode)

  implicit val regionCodec: CodecJson[Region] =
    CodecJson(_.name.asJson, jdecode1(Region(_)).decode)

  implicit val credentialsCodec: CodecJson[S3Credentials] =
    casecodec3(
      S3Credentials.apply, S3Credentials.unapply)(
      "accessKey", "secretKey", "region"
    ).setName("Credentials must include 'accessKey', 'secretKey', and 'region'")

}