package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.control.NonFatal

/** HTTP response received by the client
  *
  * @param code Response code
  * @param status Response message. This have no meaning for the HTTP connection, its just for human enjoyment.
  * @param headers Response headers
  * @param body [[BodyReader]] used to consume the response body.
  */
case class ClientResponse(code: Int, status: String, headers: Headers, body: BodyReader)
