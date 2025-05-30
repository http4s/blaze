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

package org.http4s.blaze.core

import cats.effect.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import fs2.Stream._
import fs2._
import org.http4s.Entity
import org.http4s.Entity.Empty
import org.http4s.Header
import org.http4s.Header.Raw
import org.http4s.Headers
import org.http4s.InvalidBodyException
import org.http4s.Method
import org.http4s.Request
import org.http4s.blaze.core.util._
import org.http4s.blaze.http.parser.BaseExceptions.ParserException
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.BufferTools
import org.http4s.blaze.util.BufferTools.emptyBuffer
import org.http4s.headers._
import org.http4s.syntax.header._
import org.http4s.util.Renderer
import org.http4s.util.StringWriter
import org.http4s.util.Writer

import java.nio.ByteBuffer
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** Utility bits for dealing with the HTTP 1.x protocol */
private[blaze] trait Http1Stage[F[_]] { self: TailStage[ByteBuffer] =>

  /** ExecutionContext to be used for all Future continuations
    * '''WARNING:''' The ExecutionContext should trampoline or risk possibly unhandled stack overflows
    */
  implicit protected def executionContext: ExecutionContext

  implicit protected def F: Async[F]

  implicit protected def dispatcher: Dispatcher[F]

  protected def chunkBufferMaxSize: Int

  protected def doParseContent(buffer: ByteBuffer): Option[ByteBuffer]

  protected def contentComplete(): Boolean

  /** Check Connection header and add applicable headers to response */
  @deprecated("Use checkConnectionPersistence(Option[Connection], Int, Writer) instead", "0.23.17")
  protected final def checkCloseConnection(conn: Connection, rr: StringWriter): Boolean =
    if (conn.hasKeepAlive) { // connection, look to the request
      logger.trace("Found Keep-Alive header")
      false
    } else if (conn.hasClose) {
      logger.trace("Found Connection:Close header")
      rr << "Connection:close\r\n"
      true
    } else {
      logger.info(
        s"Unknown connection header: '${conn.value}'. Closing connection upon completion."
      )
      rr << "Connection:close\r\n"
      true
    }

  /** Checks whether the connection should be closed per the request's Connection header
    * and the HTTP version.
    *
    * As a side effect, writes a "Connection: close" header to the StringWriter if
    * the request explicitly requests the connection is closed.
    *
    * @see [[https://datatracker.ietf.org/doc/html/rfc9112#name-persistence RFC 9112, Section 9.3, Persistence]]
    */
  private[http4s] final def checkRequestCloseConnection(
      conn: Option[Connection],
      minorVersion: Int,
      rr: Writer,
  ): Boolean =
    if (conn.fold(false)(_.hasClose)) {
      logger.trace(s"Closing ${conn} due to explicit close option in request's Connection header")
      // This side effect doesn't really belong here, but is relied
      // upon in multiple places as we look for the encoder.  The
      // related problem of writing the keep-alive header in HTTP/1.0
      // is handled elsewhere.
      if (minorVersion >= 1) {
        rr << "Connection: close\r\n"
      }
      true
    } else if (minorVersion >= 1) {
      logger.trace(s"Keeping ${conn} alive per default behavior of HTTP >= 1.1")
      false
    } else if (conn.fold(false)(_.hasKeepAlive)) {
      logger.trace(
        s"Keeping ${conn} alive due to explicit keep-alive option in request's Connection header"
      )
      false
    } else {
      logger.trace(s"Closing ${conn} per default behavior of HTTP/1.0")
      true
    }

  /** Get the proper body encoder based on the request */
  protected final def getEncoder(
      req: Request[F],
      rr: StringWriter,
      minor: Int,
      closeOnFinish: Boolean,
  ): Http1Writer[F] = {
    val headers = req.headers
    getEncoder(
      headers.get[Connection],
      headers.get[`Transfer-Encoding`],
      headers.get[`Content-Length`],
      req.trailerHeaders,
      rr,
      minor,
      closeOnFinish,
      Http1Stage.omitEmptyContentLength(req),
    )
  }

  /** Get the proper body encoder based on the request,
    * adding the appropriate Connection and Transfer-Encoding headers along the way
    */
  protected final def getEncoder(
      connectionHeader: Option[Connection],
      bodyEncoding: Option[`Transfer-Encoding`],
      lengthHeader: Option[`Content-Length`],
      trailer: F[Headers],
      rr: StringWriter,
      minor: Int,
      closeOnFinish: Boolean,
      omitEmptyContentLength: Boolean,
  ): Http1Writer[F] =
    lengthHeader match {
      case Some(h) if bodyEncoding.forall(!_.hasChunked) || minor == 0 =>
        // HTTP 1.1: we have a length and no chunked encoding
        // HTTP 1.0: we have a length

        bodyEncoding.foreach(enc =>
          logger.warn(
            s"Unsupported transfer encoding: '${enc.value}' for HTTP 1.$minor. Stripping header."
          )
        )

        logger.trace("Using static encoder")

        rr << h << "\r\n" // write Content-Length

        // add KeepAlive to Http 1.0 responses if the header isn't already present
        rr << (if (!closeOnFinish && minor == 0 && connectionHeader.isEmpty)
                 "Connection: keep-alive\r\n\r\n"
               else "\r\n")

        new IdentityWriter[F](h.length, this)

      case _ => // No Length designated for body or Transfer-Encoding included for HTTP 1.1
        if (minor == 0) // we are replying to a HTTP 1.0 request see if the length is reasonable
          if (closeOnFinish) { // HTTP 1.0 uses a static encoder
            logger.trace("Using static encoder")
            rr << "\r\n"
            new IdentityWriter[F](-1, this)
          } else { // HTTP 1.0, but request was Keep-Alive.
            logger.trace("Using static encoder without length")
            new CachingStaticWriter[F](
              this
            ) // will cache for a bit, then signal close if the body is long
          }
        else
          bodyEncoding match { // HTTP >= 1.1 request without length and/or with chunked encoder
            case Some(enc) => // Signaling chunked means flush every chunk
              if (!enc.hasChunked)
                logger.warn(
                  s"Unsupported transfer encoding: '${enc.value}' for HTTP 1.$minor. Stripping header."
                )

              if (lengthHeader.isDefined)
                logger.warn(
                  s"Both Content-Length and Transfer-Encoding headers defined. Stripping Content-Length."
                )

              new FlushingChunkWriter(this, trailer)

            case None => // use a cached chunk encoder for HTTP/1.1 without length of transfer encoding
              logger.trace("Using Caching Chunk Encoder")
              new CachingChunkWriter(this, trailer, chunkBufferMaxSize, omitEmptyContentLength)
          }
    }

  /** Makes a [[EntityBody]] and a function used to drain the line if terminated early.
    *
    * @param buffer starting `ByteBuffer` to use in parsing.
    * @param eofCondition If the other end hangs up, this is the condition used in the stream for termination.
    *                     The desired result will differ between Client and Server as the former can interpret
    *                     and `Command.EOF` as the end of the body while a server cannot.
    */
  protected final def collectEntityFromParser(
      buffer: ByteBuffer,
      eofCondition: () => Either[Throwable, Option[Chunk[Byte]]],
  ): (Entity[F], () => Future[ByteBuffer]) =
    if (contentComplete())
      if (buffer.remaining() == 0) Http1Stage.CachedEmptyEntity
      else (Entity.Empty, () => Future.successful(buffer))
    // try parsing the existing buffer: many requests will come as a single chunk
    else if (buffer.hasRemaining) doParseContent(buffer) match {
      case Some(buff) if contentComplete() =>
        Entity.strict(scodec.bits.ByteVector.view(buff)) -> Http1Stage.futureBufferThunk(buffer)

      case Some(buff) =>
        val (rst, end) = streamingEntity(buffer, eofCondition)
        Entity.stream(Stream.chunk(Chunk.byteBuffer(buff)) ++ rst.body) -> end

      case None if contentComplete() =>
        if (buffer.hasRemaining) Entity.Empty -> Http1Stage.futureBufferThunk(buffer)
        else Http1Stage.CachedEmptyEntity

      case None => streamingEntity(buffer, eofCondition)
    }
    // we are not finished and need more data.
    else streamingEntity(buffer, eofCondition)

  private[this] val shutdownCancelToken = Some(F.delay(stageShutdown()))

  // Streams the body off the wire
  private def streamingEntity(
      buffer: ByteBuffer,
      eofCondition: () => Either[Throwable, Option[Chunk[Byte]]],
  ): (Entity[F], () => Future[ByteBuffer]) = {
    @volatile var currentBuffer = buffer

    // TODO: we need to work trailers into here somehow
    val t = F.async[Option[Chunk[Byte]]] { cb =>
      F.delay {
        if (!contentComplete()) {
          def go(): Unit =
            try {
              val parseResult = doParseContent(currentBuffer)
              logger.debug(s"Parse result: $parseResult, content complete: ${contentComplete()}")
              parseResult match {
                case Some(result) =>
                  cb(Either.right(Chunk.byteBuffer(result).some))

                case None if contentComplete() =>
                  cb(End)

                case None =>
                  channelRead().onComplete {
                    case Success(b) =>
                      currentBuffer = BufferTools.concatBuffers(currentBuffer, b)
                      go()

                    case Failure(Command.EOF) =>
                      cb(eofCondition())

                    case Failure(t) =>
                      logger.error(t)("Unexpected error reading body.")
                      cb(Either.left(t))
                  }
              }
            } catch {
              case t: ParserException =>
                fatalError(t, "Error parsing request body")
                cb(Either.left(InvalidBodyException(t.getMessage())))

              case t: Throwable =>
                fatalError(t, "Error collecting body")
                cb(Either.left(t))
            }
          go()
        } else cb(End)
        shutdownCancelToken
      }
    }

    (Entity.stream(repeatEval(t).unNoneTerminate.flatMap(chunk(_))), () => drainBody(currentBuffer))
  }

  /** Called when a fatal error has occurred
    * The method logs an error and shuts down the stage, sending the error outbound
    * @param t
    * @param msg
    */
  protected def fatalError(t: Throwable, msg: String): Unit = {
    logger.error(t)(s"Fatal Error: $msg")
    stageShutdown()
    closePipeline(Some(t))
  }

  /** Cleans out any remaining body from the parser */
  protected final def drainBody(buffer: ByteBuffer): Future[ByteBuffer] = {
    logger.trace(s"Draining body: $buffer")

    while (!contentComplete() && doParseContent(buffer).nonEmpty) { /* NOOP */ }

    if (contentComplete()) Future.successful(buffer)
    else {
      // Send the EOF to trigger a connection shutdown
      logger.info(s"HTTP body not read to completion. Dropping connection.")
      Future.failed(Command.EOF)
    }
  }
}

object Http1Stage {
  private val CachedEmptyBufferThunk = {
    val b = Future.successful(emptyBuffer)
    () => b
  }

  private val CachedEmptyEntity = Empty -> CachedEmptyBufferThunk

  // Building the current Date header value each time is expensive, so we cache it for the current second
  private var currentEpoch: Long = _
  private var cachedString: String = _

  private val NoPayloadMethods: Set[Method] =
    Set(Method.GET, Method.DELETE, Method.CONNECT, Method.TRACE)

  private def currentDate: String = {
    val now = Instant.now()
    val epochSecond = now.getEpochSecond
    if (epochSecond != currentEpoch) {
      currentEpoch = epochSecond
      cachedString = Renderer.renderString(now)
    }
    cachedString
  }

  private def futureBufferThunk(buffer: ByteBuffer): () => Future[ByteBuffer] =
    if (buffer.hasRemaining) { () =>
      Future.successful(buffer)
    } else CachedEmptyBufferThunk

  /** Encodes the headers into the Writer. Does not encode
    * `Transfer-Encoding` or `Content-Length` headers, which are left
    * for the body encoder. Does not encode headers with invalid
    * names. Adds `Date` header if one is missing and this is a server
    * response.
    *
    * Note: this method is very niche but useful for both server and client.
    */
  def encodeHeaders(headers: Iterable[Raw], rr: Writer, isServer: Boolean): Unit = {
    var dateEncoded = false
    val dateName = Header[Date].name
    headers.foreach { h =>
      if (h.name != `Transfer-Encoding`.name && h.name != `Content-Length`.name && h.isNameValid) {
        if (isServer && h.name == dateName) dateEncoded = true
        rr << h << "\r\n"
      }
    }

    if (isServer && !dateEncoded)
      rr << dateName << ": " << currentDate << "\r\n"
    ()
  }

  private def omitEmptyContentLength[F[_]](req: Request[F]) =
    NoPayloadMethods.contains(req.method)
}
