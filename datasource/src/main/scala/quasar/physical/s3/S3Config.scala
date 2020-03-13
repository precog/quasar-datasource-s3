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
import quasar.connector.{CompressionScheme, DataFormat}

import argonaut._ , Argonaut._
import org.http4s.Uri

final case class S3Config(
    bucket: Uri,
    format: DataFormat,
    credentials: Option[S3Credentials])

final case class AccessKey(value: String)
final case class SecretKey(value: String)
final case class Region(name: String)

final case class S3Credentials(accessKey: AccessKey, secretKey: SecretKey, region: Region)

object S3Config {
  private val failureMsg =
    "Failed to parse configuration for S3 connector."


  implicit val uriCodec: CodecJson[Uri] = CodecJson(
    u => Json.jString(u.renderString),
    optionDecoder(_.as[String].toOption.flatMap(Uri.fromString(_).toOption), "Uri").decode(_))

  val legacyDecodeFlatFormat: DecodeJson[DataFormat] = DecodeJson { c => c.as[String].flatMap {
    case "array" => DecodeResult.ok(DataFormat.json)
    case "lineDelimited" => DecodeResult.ok(DataFormat.ldjson)
    case other => DecodeResult.fail(s"Unrecognized parsing format: $other", c.history)
  }}

  val legacyDecodeDataFormat: DecodeJson[DataFormat] = DecodeJson( c => for {
    parsing <- (c --\ "jsonParsing").as(legacyDecodeFlatFormat)
    compressionScheme <- (c --\ "compressionScheme").as[Option[CompressionScheme]]
  } yield compressionScheme match {
    case None => parsing
    case Some(_) => DataFormat.gzipped(parsing)
  })

  implicit val configCodec: CodecJson[S3Config] = CodecJson({ (config: S3Config) =>
    ("bucket" := config.bucket) ->:
    ("credentials" := config.credentials) ->:
    config.format.asJson
  }, (c => for {
    format <- c.as[DataFormat] ||| c.as(legacyDecodeDataFormat)

    bucket <- (c --\ "bucket").as[Uri]
    credentials <- (c --\ "credentials").as[Option[S3Credentials]]
  } yield S3Config(bucket, format, credentials))).setName(failureMsg)
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
