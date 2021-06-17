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

package quasar.physical.s3.impl


import slamdata.Predef._

import scala.util.Either

import quasar.contrib.pathy._
import quasar.physical.s3.S3Error

import cats.data.{EitherT, OptionT}
import cats.effect.Sync
import cats.implicits._
import fs2.Stream
import org.http4s.{MalformedMessageBodyFailure, Query, Status}
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.headers.`Content-Type`
import org.http4s.scalaxml.{xml => xmlDecoder}
import org.http4s.{Charset, DecodeResult, EntityDecoder, Media, MediaRange, Request, Uri}
import pathy.Path
import pathy.Path.{DirName, FileName}
import scala.xml.Elem
import scalaz.{\/-, -\/}
import shims._

object children {
  // FIXME: dir should be ADir and pathToDir should be deleted
  def apply[F[_]: Sync](client: Client[F], bucket: Uri, dir: APath)
      : F[Option[Stream[F, PathSegment]]] = {
    val msg = "Unexpected failure when streaming a multi-page response for ListBuckets"

    val stream0 =
      handleS3(fetchResults(client, bucket, dir, None)) map (results =>
        Stream.iterateEval(results) {
          case (_, next0) =>
            handleS3(fetchResults(client, bucket, dir, next0))
              .getOrElseF(Sync[F].raiseError(new Exception(msg)))
        })

    // We use takeThrough and not takeWhile since the last page of a response
    // does not include a `continuation-token`
    stream0.map(_.takeThrough(_._2.isDefined).flatMap(_._1))
      .map(toPathSegment(_, dir))
      .value
  }

  ///

  private def handleS3[F[_]: Sync, A](e: EitherT[F, S3Error, A])
      : OptionT[F, A] = e.toOption

  // FIXME parse the results as they arrive using an XML streaming parser, instead of paging
  // one response at a time
  private def fetchResults[F[_]: Sync](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    next: Option[ContinuationToken])
      : EitherT[F, S3Error, (Stream[F, APath], Option[ContinuationToken])] =
    listObjects(client, bucket, dir, next)
      .flatMap(extractList(_).toEitherT)
      .map(_.leftMap(Stream.emits(_)))

  private def toPathSegment[F[_]](s: Stream[F, APath], dir: APath): Stream[F, PathSegment] =
    s.flatMap(p => Stream.emits(Path.peel(p).toList)).map(_._2)

  private def listObjects[F[_]: Sync](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    next: Option[ContinuationToken])
      : EitherT[F, S3Error, Elem] =
    EitherT(Sync[F].recover[Either[S3Error, Elem]](
      client.expect(listingRequest(client, bucket, dir, next))(utf8Xml).map(_.asRight)) {
      case UnexpectedStatus(Status.Forbidden) =>
        S3Error.Forbidden.asLeft[Elem]
      case UnexpectedStatus(Status.MovedPermanently) =>
        S3Error.UnexpectedResponse(Status.MovedPermanently.reason).asLeft[Elem]
      case MalformedMessageBodyFailure(_, _) =>
        S3Error.MalformedResponse.asLeft[Elem]
    })

  private def listingRequest[F[_]](
    client: Client[F],
    bucket: Uri,
    dir: APath,
    ct: Option[ContinuationToken]): Request[F] = {

    // Start with the bucket URI; add an extra `/` on the end
    // so that S3 understands us.
    // `list-type=2` asks for the new version of the list api.
    // We only add the `objectPrefix` if it's not empty;
    // the S3 API doesn't understand empty `prefix`.
    val listingQuery = (bucket / "")

    val listType = ("list-type", "2").some
    // Converts a pathy Path to an S3 object prefix.
    val objectPrefix = aPathToObjectPrefix(dir)
    val prefix = objectPrefix.map(("prefix", _)).getOrElse(("prefix", "")).some
    val startAfter = objectPrefix.map(("start-after", _))
    val delimiter = ("delimiter", "/").some

    val ct0 = ct.map(_.value).map(("continuation-token", _))

    val q = Query.fromString(s3EncodeQueryParams(
      List(listType, delimiter, prefix, startAfter, ct0).unite.toMap))

    Request[F](uri = listingQuery.copy(query = q))
  }

  // Lists all objects and prefixes from a ListObjects request.
  private def extractList(doc: Elem): Either[S3Error, (List[APath], Option[ContinuationToken])] = {
    val noContentMsg = S3Error.UnexpectedResponse("XML received from AWS API has no top-level <Contents>")
    val noKeyCountMsg = S3Error.UnexpectedResponse("XML received from AWS API has no top-level <KeyCount>")
    val noKeyMsg = S3Error.UnexpectedResponse("XML received from AWS API has no <Key> elements under <Contents>")
    val noParseObjectMsg = S3Error.UnexpectedResponse("Failed to parse objects path in S3 API response")
    val noParsePrefixesMsg = S3Error.UnexpectedResponse("Failed to parse prefixes in S3 API response")

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

    val commonPrefixes = Either.catchNonFatal(doc \ "CommonPrefixes").leftMap(_ => noParsePrefixesMsg)
    val prefixes: Either[S3Error, List[String]] =
      commonPrefixes
        .map(_.toList)
        .flatMap(_.traverse[Either[S3Error, ?], String](elem =>
          Either.catchNonFatal((elem \ "Prefix").text).leftMap(_ => noParsePrefixesMsg)))
    val prefixesPaths =
      prefixes
        .flatMap(_.traverse[Either[S3Error, ?], APath](pth =>
          Either.fromOption(s3NameToPath(pth), noParsePrefixesMsg)))

    val allPaths = (prefixesPaths, childPaths) match {
      case (Left(errL), Left(errR)) =>
        S3Error.UnexpectedResponse("No prefixes or objects in response").asLeft
      case (Right(listing), Left(_)) => listing.asRight
      case (Left(_), Right(listing)) => listing.asRight
      case (Right(listingL), Right(listingR)) => (listingL ++ listingR).asRight
    }

    keyCount.flatMap(kc =>
      if (kc === 0) S3Error.NotFound.asLeft
      else allPaths.map((_, continuationToken)))
  }

  private def aPathToObjectPrefix(apath: APath): Option[String] = {
    // Don't provide an object prefix if listing the
    // entire bucket. Otherwise, we have to drop
    // the first `/`, because object prefixes can't
    // begin with `/`.
    if (apath =!= Path.rootDir) {
      pathToDir(apath).map(Path.posixCodec.printPath(_).drop(1).self)
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

  private def utf8Xml[F[_]: Sync](implicit ev: EntityDecoder[F, Elem]): EntityDecoder[F, Elem] = {
    new EntityDecoder[F, Elem] {
      override def consumes: Set[MediaRange] =
        ev.consumes
      override def decode(media: Media[F], strict: Boolean): DecodeResult[F, Elem] = {
        val utf8ContentType = media.headers.get(`Content-Type`).map(_.withCharset(Charset.`UTF-8`))
        val h = utf8ContentType.fold(media.headers)(media.headers.put(_))

        ev.decode(Media[F](media.body, h), strict)
      }
    }
  }

  private final case class ContinuationToken(value: String)

}
