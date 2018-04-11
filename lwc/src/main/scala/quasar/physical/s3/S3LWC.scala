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

import quasar.fs.mount.ConnectionUri
import quasar.mimir.{LightweightConnector, LightweightFileSystem}
import slamdata.Predef._

import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.Uri

import scalaz._
import scalaz.concurrent.Task

object S3LWC extends LightweightConnector {
  private def delayEither[E, A](a: E \/ A): EitherT[Task, E, A] = {
    EitherT.eitherT(Task.delay(a))
  }

  def init(uri: ConnectionUri): EitherT[Task, String, (LightweightFileSystem, Task[Unit])] =
    EitherT.rightT(Task.delay {
      val client = PooledHttp1Client()
      val \/-(httpUri) = Uri.fromString(uri.value)
      (new S3LWFS(httpUri, client), client.shutdown)
    })

}
