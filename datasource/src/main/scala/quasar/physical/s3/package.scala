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

import quasar.api.datasource.DatasourceType

import slamdata.Predef._

import eu.timepit.refined.auto._
import cats.{FlatMap, Show}
import cats.effect.{ExitCase, Resource}
import cats.syntax.flatMap._
import cats.syntax.functor._

sealed trait S3Error

object S3Error {
  final case object NotFound extends S3Error
  final case class UnexpectedResponse(msg: String) extends S3Error
  final case object Forbidden extends S3Error
  final case object MalformedResponse extends S3Error
}

sealed trait S3JsonParsing

object S3JsonParsing {
  case object JsonArray extends S3JsonParsing
  case object LineDelimited extends S3JsonParsing

  implicit def showS3JsonParsing: Show[S3JsonParsing] =
    Show.show {
      case JsonArray => "array"
      case LineDelimited => "lineDelimited"
    }
}

package object s3 {
  val datasourceKind: DatasourceType = DatasourceType("s3", 1L)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def resourceCleanup[F[_]: FlatMap, A](r: Resource[F, A]): F[Unit] =
    r match {
      case Resource.Allocate(a) => a map {
        case (_, release) => release(ExitCase.Completed)
      }
      case Resource.Bind(src, fs) =>
        resourceCleanup(src).flatMap(s => resourceCleanup(fs(s)))
      case Resource.Suspend(res) =>
        res.flatMap(resourceCleanup(_))
    }
}
