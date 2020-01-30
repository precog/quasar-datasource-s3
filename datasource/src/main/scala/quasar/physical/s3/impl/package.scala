/*
 * Copyright 2014–2020 SlamData Inc.
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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import cats.instances.char._
import cats.instances.option._
import cats.syntax.eq._
import org.http4s.Uri

package object impl {
  // this type comes up too many times to write out myself.
  // scala.Any is better than `_` here because existentials
  // are broken
  private type APath = pathy.Path[pathy.Path.Abs, scala.Any, pathy.Path.Sandboxed]

  // This should be used instead of the `/` method from http4's Uri
  // class since that method does standard URL encoding on the path, which
  // breaks AWS request signing for S3
  def appendPathS3Encoded(uri: Uri, newSegment: Uri.Path): Uri = {
    val sep =
      if (uri.path.isEmpty || uri.path.lastOption =!= Some('/')) "/"
      else ""
    val newPath = s"${uri.path}$sep${s3Encode(newSegment, encodeSlash = false)}"

    uri.withPath(newPath)
  }

  // S3 specific encoding, see
  // https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
  def s3Encode(s: String, encodeSlash: Boolean): String = {
    val e = URLEncoder.encode(s, StandardCharsets.UTF_8.toString)
      .replaceAll("\\+", "%20")
      .replaceAll("\\*", "%2A")
      .replaceAll("%7E", "~")
    // URLEncoder already encodes / to %2F
    if (encodeSlash) e // .. so here we already have the correct result
    else e.replaceAll("%2F", "/") // .. and here we need to decode %2F back to /
  }

  def s3EncodeQueryParams(queryParams: Map[String, String]): String =
    queryParams.toSeq
      .sortBy(_._1)
      .map({ case (k, v) => s3Encode(k, encodeSlash = true) + "=" + s3Encode(v, encodeSlash = true) })
      .mkString("&")

}
