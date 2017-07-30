package org.http4s.blaze.http

/** The prelude of a standard HTTP response
  *
  * @param code Response status code
  * @param status Response message. This has no meaning for the protocol, its purely for human enjoyment.
  * @param headers Response headers.
  */
case class HttpResponsePrelude(code: Int, status: String, headers: Headers)
