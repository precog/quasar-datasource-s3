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

import slamdata.Predef.{Stream => _, _}
import scala.Predef.identity

import fs2._

import scalaz.concurrent.{Actor, Task}
import scalaz.stream.Process
import scala.language.higherKinds
import scalaz.{-\/, \/, \/-}

// the code below was adapted from a gist originally written by Pavel Chlupacek (@pchlupacek on github)
// and shared with the community as https://gist.github.com/pchlupacek/989a2801036a9441da252726a1b4972d
private[s3] object fs2Conversion {


  def processToFs2[A](in:Process[Task,A]):Stream[Task,A] = Stream.suspend {
    import impl._
    val actor = syncActor[AttemptT, AttemptT, A]

    (Process.eval(Task.async[Boolean]{ cb => actor ! RegisterPublisher[AttemptT](cb) }) ++
    in.evalMap { a => Task.async[Boolean]{ cb => actor ! OfferChunk[AttemptT,A](Chunk.singleton(a), cb) } }
    .takeWhile(identity))
    .run.unsafePerformAsync {
      case \/-(_) =>   actor ! PublisherDone(None)
      case -\/(rsn) => actor ! PublisherDone(Some(rsn))
    }

    // stream from an actor
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def go:fs2.Stream[Task,A] = {
      Stream.eval(Task.async[Option[Chunk[A]]]{ cb =>
        actor ! RequestChunk[AttemptT, A](cb)
      }).flatMap {
        case None => Stream.empty // done
        case Some(chunk) => Stream.chunk(chunk) ++ go
      }
    }


    go
    .onError { rsn =>
      Stream.eval_(Task.delay { actor ! SubscriberDone(Some(rsn)) }) ++ Stream.fail(rsn)
    }
    .onFinalize {
      Task.delay(actor ! SubscriberDone(None))
    }
  }

  private object impl {

    type AttemptT[+A] = Throwable \/ A

    sealed trait Attempt[F[+_]] {
      def success[A](a:A):F[A]
      val continue:F[Boolean] = success(true)
      val stop:F[Boolean] = success(false)
      def failure(rsn:Throwable):F[Nothing]
      def fromResult(result:Option[Throwable]):F[Boolean] = result.map(failure).getOrElse(stop)
    }

    object Attempt {
      implicit val processInstance: Attempt[AttemptT]= new Attempt[AttemptT] {
        def success[A](a: A): AttemptT[A] = \/-(a)
        def failure(rsn: Throwable): AttemptT[Nothing] = -\/(rsn)
      }

    }

  // nothing ain't polykinded if you alias + import it from a custom predef
    final case class RegisterPublisher[F[+_]](cb: F[Boolean] => Unit) extends M[F,scala.Nothing,Nothing]
    final case class RequestChunk[F[+_], A](cb: F[Option[Chunk[A]]] => Unit) extends M[scala.Nothing,F,A]
    final case class OfferChunk[F[+_], A](chunk:Chunk[A], cb: F[Boolean] => Unit) extends M[F,scala.Nothing,A]
    final case class SubscriberDone(result:Option[Throwable]) extends M[scala.Nothing,scala.Nothing,Nothing]
    final case class PublisherDone(result:Option[Throwable]) extends M[scala.Nothing,scala.Nothing,Nothing]

    sealed trait M[+FIn[+_], +FOut[+_] ,+A]

    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    def syncActor[FIn[+_], FOut[+_], A](implicit FIn:Attempt[FIn], FOut:Attempt[FOut]): Actor[M[FIn,FOut,A]] = {

      var upReady:Option[FIn[Boolean] => Unit] = None
      var requestReady:Option[FOut[Option[Chunk[A]]] => Unit] = None
      var done:Option[Option[Throwable]] = None

      Actor.actor[M[FIn, FOut, A]]({
        case register:RegisterPublisher[FIn @ unchecked] =>
          if (requestReady.nonEmpty) register.cb(FIn.continue)
          else upReady = Some(register.cb)

        case request:RequestChunk[FOut @unchecked,A @unchecked] =>
          done match {
            case None => requestReady = Some(request.cb) ; upReady.foreach { cb => cb(FIn.continue) } ; upReady = None
            case Some(None) => request.cb(FOut.success(None))
            case Some(Some(rsn)) => request.cb(FOut.failure(rsn))
          }

        case offer:OfferChunk[FIn @unchecked ,A @unchecked] =>
          done match {
            case None => requestReady match {
              case Some(rcb) => rcb(FOut.success(Some(offer.chunk))) ; upReady = Some(offer.cb) ; requestReady = None
              case None =>
                val rsn = new Throwable(s"Downstream not ready, while requesting data : ${offer.chunk}")
                done = Some(Some(rsn))
                offer.cb(FIn.failure(rsn))
            }

            case Some(result) => offer.cb(FIn.fromResult(result))
          }

        case SubscriberDone(result) =>
          upReady.foreach { cb => cb(FIn.fromResult(result)) }
          upReady = None
          done = done orElse Some(result)

        case PublisherDone(result) =>
          requestReady.foreach { cb => result match {
            case None => cb(FOut.success(None))
            case Some(rsn) => cb(FOut.failure(rsn))
          }}
          requestReady = None
          done = done orElse Some(result)

      })(scalaz.concurrent.Strategy.Sequential)

    }


  }

}