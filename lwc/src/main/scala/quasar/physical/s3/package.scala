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

package quasar.physical

import slamdata.Predef._

import scalaz.concurrent.Task

package object s3 {

  // This is for fs2/scalaz interop.
  // It may be replaceable with fs2-scalaz.
  implicit val fs2CatchableTask: fs2.util.Catchable[Task] = new fs2.util.Catchable[Task] {
    def attempt[A](fa: Task[A]): Task[fs2.util.Attempt[A]] = fa.attempt.map(_.toEither)
    def flatMap[A, B](a: Task[A])(f: A => Task[B]): Task[B] = a.flatMap(f)
    def pure[A](a: A): Task[A] = Task.now(a)
    def fail[A](err: Throwable): Task[A] = Task.fail(err)
  }

}