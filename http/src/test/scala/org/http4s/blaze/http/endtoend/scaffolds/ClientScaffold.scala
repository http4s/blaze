package org.http4s.blaze.http.endtoend.scaffolds

import org.http4s.blaze.http.{BodyReader, Headers, HttpRequest, HttpResponsePrelude}

abstract class ClientScaffold(majorVersion: Int, minorVersion: Int) {

  def close(): Unit

  // Don't be afraid to block: this is for testing.
  def runRequest(request: HttpRequest): (HttpResponsePrelude, Array[Byte])

  final def runGet(url: String, headers: Headers = Nil): (HttpResponsePrelude, Array[Byte]) = {
    val request = HttpRequest("GET", url, majorVersion, minorVersion, headers, BodyReader.EmptyBodyReader)
    runRequest(request)
  }
}
