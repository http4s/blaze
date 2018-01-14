package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.{ClientResponse, _}
import org.http4s.blaze.http.HttpClientSession.ReleaseableResponse
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.Command.{Disconnect, OutboundCommand}
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.immutable.VectorBuilder
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private class ClientStage(request: HttpRequest, executor: ExecutionContext) extends TailStage[StreamMessage] {
  import ClientStage._

  private[this] val lock: Object = this

  private[this] var inboundEOF = false
  private[this] var released = false

  // TODO: can this be called when the inbound body has been consumed?
  // there is no handle to detect when the request body has been written, and,
  // in general, we should not expect to receive the full response before the
  // full request has been written.
  private[this] def release(cmd: OutboundCommand): Unit = lock.synchronized {
    if (!released) {
      released = true
      inboundEOF = true
      sendOutboundCommand(cmd)
    }
  }

  private[this] def inboundConsumed: Boolean = lock.synchronized { inboundEOF }

  private[this] def observeEOF(): Unit = lock.synchronized { inboundEOF = true }

  private class ReleasableResponseImpl(
      code: Int, status: String, headers: Headers, body: BodyReader)
    extends ClientResponse(code, status, headers, body) with ReleaseableResponse {
    override def release(): Unit = ClientStage.this.release(Command.Disconnect)
  }

  override def name: String = "Http2ClientTail"

  private[this] val _result = Promise[ReleaseableResponse]

  def result: Future[ReleaseableResponse] = _result.future

  override protected def stageStartup(): Unit = makeHeaders(request) match {
    case Failure(t) => shutdownWithError(t, "Failed to construct valid request")
    case Success(hs) =>
      val eos = request.body.isExhausted
      val headerFrame = HeadersFrame(Priority.NoPriority, eos, hs)

      channelWrite(headerFrame).onComplete {
        case Success(_) =>
          if (!eos) writeBody(request.body)

          readResponseHeaders()

        case Failure(ex) => shutdownWithError(ex, "writeHeaders")
      }(Execution.directec)
  }

  private def writeBody(body: BodyReader): Unit = {
    def go(): Future[Unit] = {
      body().flatMap { b =>
        val eos = b.hasRemaining
        val frame = DataFrame(eos, b)
        val f = channelWrite(frame)
        if (!eos) f.flatMap(_ => go())(executor)
        else f
      }(Execution.trampoline)
    }

    // The body writing computation is orphaned: if it completes that great, if not
    // thats also fine. Errors should be propagated via the response or errors.
    go().onComplete { _ => body.discard() }(Execution.directec)
  }

  private def readResponseHeaders(): Unit = channelRead().onComplete {
    case Success(HeadersFrame(_, eos, hs)) =>
      val body = if (eos) BodyReader.EmptyBodyReader else responseBody()
      // TODO: we need to make sure this wasn't a 1xx response
      _result.tryComplete(collectResponseFromHeaders(body, hs))

    case Success(other) =>
      // The first frame must be a HEADERS frame, either of an informational
      // response or the message prelude
      // https://tools.ietf.org/html/rfc7540#section-8.1
      val ex = new IllegalStateException(s"HTTP2 response started with message other than headers: $other")
      shutdownWithError(ex, "readResponseHeaders")

    case Failure(ex) => shutdownWithError(ex, "readResponseHeaders")
  }(Execution.trampoline)

  private def responseBody(): BodyReader = new BodyReader {

    // We don't want to call `release()` here because we may be waiting for this message
    // to be written, so we don't want to close the stream
    override def discard(): Unit = observeEOF()

    override def isExhausted: Boolean = inboundConsumed

    override def apply(): Future[ByteBuffer] = {
      if (inboundConsumed) BufferTools.emptyFutureBuffer
      else channelRead().map {
        // TODO: do we need to care about the EOS? I believe its taken care of upstream
        case d@DataFrame(eos, data) =>
          if (eos) discard()
          logger.debug(s"Received data frame: $d")
          data

        // TODO: how do we expect to handle trailers? They are pretty important to http2...
        case other =>
          logger.debug(s"Received frame other than data: $other. Discarding remainder of body.")
          discard()
          BufferTools.emptyBuffer
      }(Execution.directec) // TODO: this seems to surface a strange error if its trampoline EC!
    }
  }

  private def collectResponseFromHeaders(body: BodyReader, hs: Seq[(String, String)]): Try[ReleaseableResponse] = {
    logger.debug(s"Received response headers: $hs")

    val regularHeaders = new VectorBuilder[(String, String)]
    var pseudos = true
    var statusCode = -1

    val it = hs.iterator
    while (it.hasNext) {
      val pair@(k, v) = it.next()

      if (!k.startsWith(":")) {
          pseudos = false // definitely not in pseudos anymore
          regularHeaders += pair
      } else if (!pseudos) {
        return Failure(new Exception("Pseudo headers were not contiguous"))
      } else k match {
          // Matching on pseudo headers now
        case StageTools.Status =>
          if (statusCode != -1)
            return Failure(new Exception("Multiple status code HTTP2 pseudo headers detected in response"))

          try statusCode = v.toInt
          catch { case ex: NumberFormatException => return Failure(ex) }

        case _ => // don't care about other pseudo headers
      }
    }

    if (statusCode != -1) Success(new ReleasableResponseImpl(statusCode, "UNKNOWN", regularHeaders.result(), body))
    else {
      val ex = Http2Exception.PROTOCOL_ERROR.rst(-1, "HTTP2 Response headers didn't include a status code.")
      release(Command.Error(ex))
      Failure(ex)
    }
  }

  private[this] def shutdownWithError(ex: Throwable, phase: String): Unit = {
    // TODO: what should be the log level here?
    logger.debug(ex)(s"$name shutting down due to error in phase $phase")
    if (_result.tryFailure(ex)) {
      // Since the user won't be getting a `ReleasableResponse`, it is our job
      // to close down the stream.
      val command = if (ex == Command.EOF) Disconnect else Command.Error(ex)
      release(command)
    }
  }
}

private object ClientStage {
  def makeHeaders(request: HttpRequest): Try[Vector[(String, String)]] = {
    UrlComposition(request.url).map { breakdown =>
      val hs = new VectorBuilder[(String, String)]

      // h2 pseudo headers
      hs += StageTools.Method -> request.method.toUpperCase
      hs += StageTools.Scheme -> breakdown.scheme
      hs += StageTools.Authority -> breakdown.authority
      hs += StageTools.Path -> breakdown.fullPath

      hs ++= request.headers
      hs.result()
    }
  }
}
