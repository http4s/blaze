package org.http4s.blaze.http

/** Standard HTTP request
  *
  * @param method HTTP request method
  * @param uri request uri
  * @param headers request headers
  * @param body function which returns the next chunk of the request body. Termination is
  *             signaled by an __empty__ `ByteBuffer` as determined by `ByteBuffer.hasRemaining()`.
  */
case class HttpRequest(method: Method, uri: Uri, majorVersion: Int, minorVersion: Int, headers: Headers, body: MessageBody)
