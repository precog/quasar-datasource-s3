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

package quasar.physical.s3.impl

import quasar.physical.s3.S3Config

import slamdata.Predef._

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.option._
import org.http4s.client.Client
import org.http4s.headers.Location
import org.http4s.{Method, Request, Status}

object isLive {
  def apply[F[_]: Applicative](client: Client[F], config: S3Config): F[Option[S3Config]] = {
    client.fetch(Request[F](uri = config.bucket, method = Method.HEAD))(resp => resp.status match {
      case Status.Ok => none.pure[F]
      case Status.MovedPermanently => resp.headers.get(Location) match {
        case Some(loc) => config.copy(bucket = loc.uri).some.pure[F]
        case None => none.pure[F]
      }
      case _ => none.pure[F]
    })
  }
}
