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
import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.option._
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.headers.Location
import org.http4s.{Method, Request, Status}

object preflightCheck {
  def apply[F[_]: Sync](client: Client[F], config: S3Config)
      : F[Option[S3Config]] =
    OptionT(followRedirects(client, config))
      .map(newUri => config.copy(bucket = newUri))
      .value

  private def followRedirects[F[_]: Sync](client: Client[F], config: S3Config): F[Option[Uri]] =
    redirectFor(client, config.bucket).flatMap {
      case redirect @ Some((Status.MovedPermanently | Status.PermanentRedirect, _)) =>
        Stream.iterateEval[F, Option[(Status, Uri)]](redirect) {
          case Some((_, u)) => redirectFor(client, u)
          case _ => none.pure[F]
        }.take(4).filter {
          case Some((Status.Ok, u)) => true
          case _ => false
        }.unNone.map(_._2).compile.last
      case _ => none.pure[F]
    }

  private def redirectFor[F[_]: Applicative](client: Client[F], u: Uri)
      : F[Option[(Status, Uri)]] = {
    client.fetch(Request[F](uri = u, method = Method.HEAD))(resp => resp.status match {
      case status @ (Status.MovedPermanently | Status.PermanentRedirect) =>
        resp.headers.get(Location).map(loc => (status, loc.uri)).pure[F]
      case Status.Ok =>
        (Status.Ok, u).some.pure[F]
      case _ =>
        none.pure[F]
    })
  }
}
