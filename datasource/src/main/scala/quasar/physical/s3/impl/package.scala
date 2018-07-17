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
  // class since that method does URL encoding on the path, which
  // breaks AWS request signing for S3
  def appendPathUnencoded(uri: Uri, newSegment: Uri.Path): Uri = {
    val newPath =
      if (uri.path.isEmpty || uri.path.lastOption =!= Some('/')) s"${uri.path}/$newSegment"
      else s"${uri.path}$newSegment"

    uri.withPath(newPath)
  }
}
