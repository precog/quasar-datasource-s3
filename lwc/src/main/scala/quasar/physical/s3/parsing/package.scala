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

// from circe/circe-fs2 0.9.1
// this can be removed in favor of circe-fs2 once we update
// jawn to 0.11.0 in quasar.

package quasar.physical.s3

import slamdata.Predef.{Seq => _, _}
import scala.collection.Seq

import fs2.{ Chunk, Pipe, Stream }
import jawn.{ AsyncParser, ParseException }
import io.circe.{Decoder, Json}
import io.circe.jawn.CirceSupportParser

package object parsing {
  final def stringArrayParser[F[_]]: Pipe[F, String, Json] = stringParser(AsyncParser.UnwrapArray)

  final def stringStreamParser[F[_]]: Pipe[F, String, Json] = stringParser(AsyncParser.ValueStream)

  final def byteArrayParser[F[_]]: Pipe[F, Byte, Json] = byteParser(AsyncParser.UnwrapArray)

  final def byteStreamParser[F[_]]: Pipe[F, Byte, Json] = byteParser(AsyncParser.ValueStream)

  final def byteArrayParserC[F[_]]: Pipe[F, Chunk[Byte], Json] = byteParserC(AsyncParser.UnwrapArray)

  final def byteStreamParserC[F[_]]: Pipe[F, Chunk[Byte], Json] = byteParserC(AsyncParser.ValueStream)

  final def stringParser[F[_]](mode: AsyncParser.Mode): Pipe[F, String, Json] =
    new ParsingPipe[F, String] {
      protected[this] final def parseWith(p: AsyncParser[Json])(in: String)
          : Either[ParseException, Seq[Json]] =
        p.absorb(in)(CirceSupportParser.facade)

      protected[this] val parsingMode: AsyncParser.Mode = mode
    }

  final def byteParserC[F[_]](mode: AsyncParser.Mode): Pipe[F, Chunk[Byte], Json] =
    new ParsingPipe[F, Chunk[Byte]] {
      protected[this] final def parseWith(p: AsyncParser[Json])(in: Chunk[Byte])
          : Either[ParseException, Seq[Json]] =
        p.absorb(in.toArray)(CirceSupportParser.facade)

      protected[this] val parsingMode: AsyncParser.Mode = mode
    }

  final def byteParser[F[_]](mode: AsyncParser.Mode): Pipe[F, Byte, Json] =
    _.chunks.through(byteParserC(mode))

  final def decoder[F[_], A](implicit decode: Decoder[A]): Pipe[F, Json, A] =
    _.flatMap { json =>
      decode(json.hcursor) match {
        case Left(df) => Stream.raiseError(df)
        case Right(a) => Stream.emit(a)
      }
    }
}
