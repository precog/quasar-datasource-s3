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
import quasar.physical.s3.impl.s3EncodeQueryParams

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{LocalDateTime, OffsetDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import cats.effect.{Bracket, Effect, Resource, Sync}
import cats.implicits._
import fs2.Stream
import org.http4s.client.Client
import org.http4s.headers.{Authorization, Date}
import org.http4s.{Header, Headers, Method, Request, Response, Uri}

/**
  * Extracted from aws4s: https://github.com/aws4s/aws4s
  */

/**
  * Based on https://github.com/ticofab/aws-request-signer,
  * inspired by: https://github.com/inreachventures/aws-signing-request-interceptor
  */
object RequestSigning {
  private val hashAlg = "SHA-256"

  private def sha256Stream[F[_]: Sync](payload: Stream[F, Byte]): F[Array[Byte]] =
    payload.chunks.compile.fold(MessageDigest.getInstance(hashAlg))((md, chunk) => { md.update(chunk.toArray); md }).map(_.digest)

  private def sha256(payload: Array[Byte]): Array[Byte] = {
    val md: MessageDigest = MessageDigest.getInstance(hashAlg)
    md.update(payload)
    md.digest
  }

  private def base16(data: Array[Byte]): String = {
    val BASE16MAP = List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
    data.flatMap(byte => Array(BASE16MAP(byte >> 4 & 0xF), BASE16MAP(byte & 0xF))).mkString
  }

  private def renderCanonicalQueryString(queryParams: Map[String, String]): String =
    s3EncodeQueryParams(queryParams)

  private def hmacSha256(data: String, key: Array[Byte]): Array[Byte] = {
    val macAlg = "HmacSHA256"
    val mac: Mac = Mac.getInstance(macAlg)

    mac.init(new SecretKeySpec(key, macAlg))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
  }

  private def renderCanonicalHeaders(headers: Headers): String =
    headers.toList
      .sortBy(_.name.value.toLowerCase)
      .map(h => s"${h.name.value.toLowerCase}:${h.value}\n")
      .mkString

  private def xAmzDateHeader(d: LocalDateTime): Header =
    Header("x-amz-date", d.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")))

  private def xAmzSecurityTokenHeader(tokenValue: String): Header =
    Header("x-amz-security-token", tokenValue)

  private def xAmzContentSha256(content: String): Header =
    Header("x-amz-content-sha256", content)

  private def sign(stringToSign: String, now: LocalDateTime, credentials: Credentials, region: Region, service: ServiceName): String = {

    val key: Array[Byte] = {
      val kSecret:  Array[Byte] = ("AWS4" + credentials.secretKey.value).getBytes(StandardCharsets.UTF_8)
      val kDate:    Array[Byte] = hmacSha256(now.format(DateTimeFormatter.BASIC_ISO_DATE), kSecret)
      val kRegion:  Array[Byte] = hmacSha256(region.name, kDate)
      val kService: Array[Byte] = hmacSha256(service.name, kRegion)
      hmacSha256("aws4_request", kService)
    }

    base16(hmacSha256(stringToSign, key))
  }
}

final case class RequestSigning(
    credentials:    Credentials,
    region:         Region,
    service:        ServiceName,
    payloadSigning: PayloadSigning,
    clock:          LocalDateTime
) {

  import RequestSigning._

  def signedHeaders[F[_]: Sync](req: Request[F]): F[Headers] =
    signHeaders(req.uri.path, req.method, req.params, req.headers, req.body)

  def signHeaders[F[_]: Sync](path: Uri.Path, method: Method, queryParams: Map[String, String], headers: Headers, payload: Stream[F, Byte]): F[Headers] = {

    val now: LocalDateTime = clock
    val credentialsNow = credentials

    val extraSecurityHeaders: Headers =
      Headers(credentialsNow.sessionToken.toList map xAmzSecurityTokenHeader)

    val extraDateHeaders: Headers =
      if (!headers.iterator.exists(_.name === Date.name)) Headers(xAmzDateHeader(now)) else Headers()

    val signedHeaders = headers ++ extraDateHeaders ++ extraSecurityHeaders

    val signedHeaderKeys = signedHeaders.toList.map(_.name.value.toLowerCase).sorted.mkString(";")

    val sha256Payload: F[String] =
      payloadSigning match {
        case PayloadSigning.Unsigned => "UNSIGNED-PAYLOAD".pure[F]
        case PayloadSigning.Signed   => sha256Stream(payload) map base16
      }

    sha256Payload map { payloadHash =>
      val canonicalRequest =
        List(
          method.toString,
          path.toString,
          renderCanonicalQueryString(queryParams),
          renderCanonicalHeaders(signedHeaders),
          signedHeaderKeys,
          payloadHash,
        ).mkString("\n")

      val credentialScope: String =
        List(
          now.format(DateTimeFormatter.BASIC_ISO_DATE),
          region.name,
          service.name + "/aws4_request"
        ).mkString("/")

      val stringToSign =
        List(
          "AWS4-HMAC-SHA256",
          now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")),
          credentialScope,
          base16(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)))
        ).mkString("\n")

      val signature = sign(stringToSign, now, credentialsNow, region, service)

      val authorizationHeaderValue =
        "AWS4-HMAC-SHA256 Credential=" +
          credentialsNow.accessKey.value + "/" + credentialScope +
          ", SignedHeaders=" + signedHeaderKeys +
          ", Signature=" + signature

      val payloadChecksumHeader: Header =
        payloadSigning match {
          case PayloadSigning.Unsigned => xAmzContentSha256("UNSIGNED-PAYLOAD")
          case PayloadSigning.Signed   => xAmzContentSha256(payloadHash)
        }

      signedHeaders.put(
        Header(Authorization.name.value, authorizationHeaderValue),
        payloadChecksumHeader,
      )
    }
  }
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class Credentials(
    accessKey:    AccessKey,
    secretKey:    SecretKey,
    sessionToken: Option[String] = None)

abstract class ServiceName(val name: String)

object ServiceName {
  object S3 extends ServiceName("s3")
}

sealed trait PayloadSigning

object PayloadSigning {

  /** Payload is signed. Note that it will cause the payload stream to be consumed twice. */
  case object Signed extends PayloadSigning

  /** Payload is not signed. Use only if consuming the payload twice would be problematic. */
  case object Unsigned extends PayloadSigning
}

object AwsV4Signing {
  def apply[F[_]: Bracket[?[_], Throwable]: Effect](conf: S3Config)(client: Client[F]): Client[F] = {
    def signRequest: Request[F] => F[Request[F]] =
      conf.credentials match {
        case Some(creds) => {
          val requestSigning = for {
            time <- Effect[F].delay(OffsetDateTime.now())
            datetime <- Effect[F].catchNonFatal(
              LocalDateTime.ofEpochSecond(time.toEpochSecond, 0, ZoneOffset.UTC))
            signing = RequestSigning(
              Credentials(creds.accessKey, creds.secretKey, None),
              creds.region,
              ServiceName.S3,
              PayloadSigning.Signed,
              datetime)
          } yield signing

          req => {
            // Requests that require signing also require `host` to always be present
            val req0 = req.uri.host match {
              case Some(host) => req.withHeaders(Headers(Header("host", host.value)))
              case None => req
            }

            requestSigning >>= (_.signedHeaders[F](req0).map(req0.withHeaders(_)))
          }
        }
        case None => req => req.pure[F]
      }

    def signAndSubmit: Request[F] => Resource[F, Response[F]] =
      (req => Resource.suspend(signRequest(req).map(client.run(_))))

    Client(signAndSubmit)
  }
}
