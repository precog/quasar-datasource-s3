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

package quasar.physical.s3.parsing

import slamdata.Predef.{Seq => _, _}
import scala.collection.Seq

import fs2.{ Pipe, Pull, Segment, Stream }
import jawn.{ AsyncParser, ParseException }
import io.circe.{ Json, ParsingFailure }
import io.circe.jawn.CirceSupportParser

private[parsing] abstract class ParsingPipe[F[_], S] extends Pipe[F, S, Json] {

  protected[this] def parsingMode: AsyncParser.Mode

  protected[this] def parseWith(parser: AsyncParser[Json])(in: S)
      : Either[ParseException, Seq[Json]]

  private[this] final def makeParser: AsyncParser[Json] =
    CirceSupportParser.async(mode = parsingMode)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private[this] final def doneOrLoop[A](p: AsyncParser[Json])(s: Stream[F, S])
      : Pull[F, Json, Unit] =
    s.pull.uncons1.flatMap {
      case Some((s, h)) => parseWith(p)(s) match {
        case Left(error) =>
          Pull.raiseError(ParsingFailure(error.getMessage, error))
        case Right(js) =>
          Pull.output(Segment.seq(js)) >> doneOrLoop(p)(h)
      }
      case None => Pull.done
    }

  final def apply(s: Stream[F, S]): Stream[F, Json] =
    doneOrLoop(makeParser)(s).stream
}
