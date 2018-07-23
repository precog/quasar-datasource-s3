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


import slamdata.Predef._

import scala.util.Either

import quasar.contrib.pathy._
import quasar.physical.s3.S3Error

import cats.data.{EitherT, OptionT}
import cats.effect.{Sync, Timer, Effect}
import cats.instances.either._
import cats.instances.int._
import cats.instances.list._
import cats.instances.option._
import cats.syntax.alternative._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._
import fs2.Stream
import org.http4s.{Uri, Request}
import org.http4s.client.Client
import org.http4s.scalaxml.{xml => xmlDecoder}
import pathy.Path
import pathy.Path.{DirName, FileName}
import scala.xml.Elem
import scalaz.{\/-, -\/}
import shims._

object children {
  // S3 provides a recursive listing (akin to `find` or
  // `dirtree`); we filter out children that aren't direct
  // children. We can only list 1000 keys, and need pagination
  // to do more. That's 1000 *recursively listed* keys, so we
  // could conceivably list *none* of the direct children of a
  // folder without pagination, depending on the order AWS
  // sends them in.
  //
  // FIXME: dir should be ADir and pathToDir should be deleted
  def apply[F[_]: Effect: Timer](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    next: Option[ContinuationToken],
    sign: Request[F] => F[Request[F]])
      : F[Option[Stream[F, PathSegment]]] = {
    val msg = "Unexpected failure when streaming a multi-page response for ListBuckets"
    val stream0 =
      handleS3(stream(client, bucket, dir, next, sign)) map (results =>
        Stream.iterateEval(results) {
          case (_, next0) =>
            handleS3(stream(client, bucket, dir, next0, sign))
              .getOrElseF(Sync[F].raiseError(new Exception(msg)))
        })

    stream0.map(s =>
      s.takeThrough { case (_, ct) => ct.isDefined }
        .flatMap { case (l, _) => Stream.emits(l) })
      .map(toPathSegment(_, dir))
      .value
  }
  ///

  // converts non-recoverable errors to runtime errors
  private def handleS3[F[_]: Effect: Timer, A](e: EitherT[F, S3Error, A]): OptionT[F, A] =
    OptionT(e.value >>= {
      case Left(S3Error.UnexpectedResponse(msg)) => Sync[F].raiseError(new Exception(msg))
      case Left(S3Error.NotFound) => none.pure[F]
      case Right(a) => a.some.pure[F]
    })

  private def stream[F[_]: Effect: Timer](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    next: Option[ContinuationToken],
    sign: Request[F] => F[Request[F]])
      : EitherT[F, S3Error, (List[APath], Option[ContinuationToken])] =
    EitherT(listObjects(client, bucket, dir, next, sign).map(extractList(_)))

  private def toPathSegment[F[_]](s: Stream[F, APath], dir: APath): Stream[F, PathSegment] =
    s.filter(path => Path.parentDir(path) === pathToDir(dir))
      .filter(path => path =!= dir)
      .flatMap(p => Stream.emits(Path.peel(p).toList))
      .map(_._2)

  private def listObjects[F[_]: Effect: Timer](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    next: Option[ContinuationToken],
    sign: Request[F] => F[Request[F]])
      : F[Elem] =
    sign(listingRequest(client, bucket, dir, next)) >>= (client.expect[Elem](_))

  private def listingRequest[F[_]](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    ct: Option[ContinuationToken]): Request[F] = {
    // Converts a pathy Path to an S3 object prefix.
    val objectPrefix = aPathToObjectPrefix(dir)

    // Start with the bucket URI; add an extra `/` on the end
    // so that S3 understands us.
    // `list-type=2` asks for the new version of the list api.
    // We only add the `objectPrefix` if it's not empty;
    // the S3 API doesn't understand empty `prefix`.
    val listingQuery = (bucket / "")

    val listType = ("list-type", "2").some
    val prefix = objectPrefix.map(("prefix", _))
    val ct0 = ct.map(_.value).map(("continuation-token", _))

    val params = List(listType, prefix, ct0).unite

    val queryUri = params.foldLeft(listingQuery) {
      case (uri0, (param, value)) => uri0.withQueryParam(param, value)
    }

    Request[F](uri = queryUri)
  }

  // Lists all objects and prefixes from a ListObjects request. This needs to be filtered
  private def extractList(doc: Elem): Either[S3Error, (List[APath], Option[ContinuationToken])] = {
    val noContentMsg = S3Error.UnexpectedResponse("XML received from AWS API has no top-level <Contents>")
    val noKeyCountMsg = S3Error.UnexpectedResponse("XML received from AWS API has no top-level <KeyCount>")
    val noKeyMsg = S3Error.UnexpectedResponse("XML received from AWS API has no <Key> elements under <Contents>")
    val noParseObjectMsg = S3Error.UnexpectedResponse("Failed to parse object path in S3 API response")

    val contents =
      Either.catchNonFatal(doc \ "Contents").leftMap(_ => noContentMsg)
    val keyCount =
      Either.catchNonFatal((doc \ "KeyCount").text.toInt).leftMap(_ => noKeyCountMsg)

    val continuationToken =
      Either.catchNonFatal((doc \ "NextContinuationToken").text)
        .toOption.filter(_.nonEmpty).map(ContinuationToken(_))

    val children: Either[S3Error, List[String]] =
      contents
        .map(_.toList)
        .flatMap(_.traverse[Either[S3Error, ?], String](elem =>
          Either.catchNonFatal((elem \ "Key").text).leftMap(_ => noKeyMsg)))

    val childPaths =
      children
        .flatMap(_.traverse[Either[S3Error, ?], APath](pth =>
          Either.fromOption(s3NameToPath(pth), noParseObjectMsg)))

    keyCount.flatMap(kc =>
      if (kc === 0) S3Error.NotFound.asLeft
      else childPaths.map((_, continuationToken)))
  }

  private def aPathToObjectPrefix(apath: APath): Option[String] = {
    // Don't provide an object prefix if listing the
    // entire bucket. Otherwise, we have to drop
    // the first `/`, because object prefixes can't
    // begin with `/`.
    if (apath != Path.rootDir) {
      Path.posixCodec.printPath(apath).drop(1).self.some // .replace("/", "%2F")
    }
    else {
      none[String]
    }
  }

  private def s3NameToPath(name: String): Option[APath] = {
    val unsandboxed: Option[Path[Path.Abs, Any, Path.Unsandboxed]] =
      // Parse S3 object paths as POSIX paths with a missing
      // slash at the beginning. Relative paths are disallowed
      // by the `_ => none` cases.
      Path.posixCodec.parsePath(
        _ => none,
        _.some,
        _ => none,
        _.some
      )("/" + name)
    // unsafeSandboxAbs is from quasar.
    // `posixCodec` parses unsandboxed ("real") paths;
    // we don't use them here.
    unsandboxed.map(unsafeSandboxAbs)
  }

  private def pathToDir(path: APath): Option[ADir] =
    Path.peel(path) match {
      case Some((d, \/-(FileName(fn)))) => (d </> Path.dir(fn)).some
      case Some((d, -\/(DirName(dn)))) => (d </> Path.dir(dn)).some
      case None if Path.depth(path) === 0 => Path.rootDir.some
      case _ => none
    }

  private final case class ContinuationToken(value: String)

}
