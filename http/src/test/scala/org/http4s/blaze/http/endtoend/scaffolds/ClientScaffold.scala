/*
 * Copyright 2014 http4s.org
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

package org.http4s.blaze.http.endtoend.scaffolds

import org.http4s.blaze.http.endtoend.scaffolds.ClientScaffold.Response
import org.http4s.blaze.http.{BodyReader, Headers, HttpRequest, HttpResponsePrelude}

/** Client implementation for testing
  *
  * Implementations are blocking, or at least block on the final result.
  */
private[endtoend] abstract class ClientScaffold(majorVersion: Int, minorVersion: Int) {
  def close(): Unit

  // Don't be afraid to block: this is for testing.
  def runRequest(request: HttpRequest): Response

  final def runGet(url: String, headers: Headers = Nil): Response = {
    val request =
      HttpRequest("GET", url, majorVersion, minorVersion, headers, BodyReader.EmptyBodyReader)
    runRequest(request)
  }

  final def runPost(url: String, bodyReader: BodyReader, headers: Headers = Nil): Response = {
    val request = HttpRequest("POST", url, majorVersion, minorVersion, headers, bodyReader)
    runRequest(request)
  }
}

object ClientScaffold {
  case class Response(prelude: HttpResponsePrelude, body: Array[Byte])
}
