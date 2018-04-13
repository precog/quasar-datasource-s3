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

package quasar.physical.s3.parsing

import slamdata.Predef.{Seq => _, _}
import scala.collection.Seq

import _root_.fs2.{ Chunk, Handle, Pipe, Pull, Stream }
import _root_.jawn.{ AsyncParser, ParseException }
import io.circe.{ Json, ParsingFailure }
import io.circe.jawn.CirceSupportParser

private[parsing] abstract class ParsingPipe[F[_], S] extends Pipe[F, S, Json] {
  protected[this] def parsingMode: AsyncParser.Mode

  protected[this] def parseWith(parser: AsyncParser[Json])(in: S): Either[ParseException, Seq[Json]]

  private[this] final def makeParser: AsyncParser[Json] = CirceSupportParser.async(mode = parsingMode)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private[this] final def doneOrLoop[A](p: AsyncParser[Json])(h: Handle[F, S]): Pull[F, Json, Unit] =
    h.receive1 {
      case (s, h) => parseWith(p)(s) match {
        case Left(error) =>
          Pull.fail(ParsingFailure(error.getMessage, error))
        case Right(js) =>
          Pull.output(Chunk.seq(js)) >> doneOrLoop(p)(h)
      }
    }

  final def apply(s: Stream[F, S]): Stream[F, Json] = s.pull(doneOrLoop(makeParser))
}
