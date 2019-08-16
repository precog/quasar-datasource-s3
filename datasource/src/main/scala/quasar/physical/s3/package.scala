/*
 * Copyright 2014–2019 SlamData Inc.
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

import slamdata.Predef._

import quasar.api.datasource.DatasourceType

import eu.timepit.refined.auto._

sealed trait S3Error

object S3Error {
  final case object NotFound extends S3Error
  final case class UnexpectedResponse(msg: String) extends S3Error
  final case object Forbidden extends S3Error
  final case object MalformedResponse extends S3Error
}

package object s3 {
  val datasourceKind: DatasourceType = DatasourceType("s3", 1L)
}
