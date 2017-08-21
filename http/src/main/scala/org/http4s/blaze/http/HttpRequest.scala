package org.http4s.blaze.http

/** Standard HTTP request
  *
  * @param method HTTP request method
  * @param url request url
  * @param headers request headers
  * @param body function which returns the next chunk of the request body. Termination is
  *             signaled by an __empty__ `ByteBuffer` as determined by `ByteBuffer.hasRemaining()`.
  */
case class HttpRequest(method: Method, url: Url, majorVersion: Int, minorVersion: Int, headers: Headers, body: BodyReader)
