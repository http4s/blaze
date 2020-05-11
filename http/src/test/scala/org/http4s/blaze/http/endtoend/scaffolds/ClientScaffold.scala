/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
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
