package org.http4s.blaze.http

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.util.HeaderTools
import org.http4s.blaze.http.util.HeaderTools.SpecialHeaders
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.{BufferTools, Execution}
import org.log4s.getLogger

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * Parses messages from the pipeline.
  *
  * This construct does not concern itself with the lifecycle of the
  * provided pipeline: it does not close it or send commands. It will
  * forward errors that occur during read and write operations by way
  * of the return values.
  */
private final class HttpCodec(maxNonBodyBytes: Int, pipeline: TailStage[ByteBuffer]) {
  import HttpCodec._

  private[this] val parser = new BlazeServerParser[(String, String)](maxNonBodyBytes)
  private[this] var buffered: ByteBuffer = BufferTools.emptyBuffer

  // Counter to allow certain machinery to identify the message exchange we are in
  private[this] var requestId: Long = 0L

  private[this] val lock = parser

  def readyForNextRequest(): Boolean = lock.synchronized {
    parser.isReset()
  }

  // TODO: how may of these locks are necessary? The only time things may be happening concurrently
  // TODO: is when users are mishandling the body encoder.
  def getRequest(): Future[HttpRequest] = lock.synchronized {
    if (parser.isReset()) {
      try {
        val req = maybeGetRequest()
        if (req != null) Future.successful(req)
        else {
          val p = Promise[HttpRequest]
          readAndGetRequest(p)
          p.future
        }
      } catch { case t: Throwable =>
        shutdown()
        Future.failed(t)
      }
    } else {
      val msg = "Attempted to get next request when protocol was in invalid state"
      Future.failed(new IllegalArgumentException(msg))
    }
  }

  def renderResponse(response: RouteAction, forceClose: Boolean): Future[RouteResult] = {
    response.handle(getEncoder(forceClose, _))
  }

  private def getEncoder(forceClose: Boolean, prelude: HttpResponsePrelude): InternalWriter = lock.synchronized {
    val minorVersion = parser.getMinorVersion()
    val sb = new StringBuilder(512)
    sb.append("HTTP/1.").append(minorVersion).append(' ')
      .append(prelude.code).append(' ')
      .append(prelude.status).append("\r\n")

    // Renders the headers except those that will modify connection state and data encoding
    val sh@SpecialHeaders(_, _, connection) = HeaderTools.renderHeaders(sb, prelude.headers)

    val keepAlive = connection match {
      case _ if forceClose => false
      case Some(value) => HeaderTools.isKeepAlive(value, minorVersion)
      case None => minorVersion != 0
    }

    if (!keepAlive) sb.append("connection: close\r\n")
    else if (minorVersion == 0) sb.append("connection: keep-alive\r\n")

    sh match {
      case SpecialHeaders(Some(te), _, _) if te.equalsIgnoreCase("chunked") =>
        new ChunkedBodyWriter(forceClose, sb, -1)

      case SpecialHeaders(_, Some(len), _) =>
        try {
          new StaticBodyWriter(forceClose, sb, len.toLong)
        } catch { case ex: NumberFormatException =>
          logger.warn(ex)(s"Content length header has invalid length: '$len'. Reverting to undefined content length")
          new SelectingWriter(forceClose, sb)
        }

      case _ => new SelectingWriter(forceClose, sb)
    }
  }

  private def getBody(): MessageBody = {
    if (parser.contentComplete()) {
      MessageBody.emptyMessageBody
    }
    else {
      new MessageBody {
        // We store the request that this body is associated with. This is
        // to avoid the situation where a user stores the reader and attempts
        // to use it later, resulting in a corrupt HTTP protocol
        private val thisRequest = requestId

        override def apply(): Future[ByteBuffer] = lock.synchronized {
          if (thisRequest != requestId || parser.contentComplete()) {
            BufferTools.emptyFutureBuffer
          }
          else {
            val buf = parser.parseBody(buffered)
            if (buf.hasRemaining) Future.successful(buf)
            else if (parser.contentComplete()) BufferTools.emptyFutureBuffer
            else {
              // need more data
              pipeline.channelRead().flatMap(buffer => lock.synchronized {
                buffered = BufferTools.concatBuffers(buffered, buffer)
                apply()
              })(Execution.trampoline)
            }
          }
        }
      }
    }
  }

  // Must be called from within a `lock.synchronized` block
  private[this] def readAndGetRequest(p: Promise[HttpRequest]): Unit = {
    // we need to get more data
    pipeline.channelRead().onComplete {
      case Success(buff)    =>
        lock.synchronized {
          try {
            buffered = BufferTools.concatBuffers(buffered, buff)
            val httpRequest = maybeGetRequest()
            if (httpRequest != null) {
              p.trySuccess(httpRequest)
            }
            else {
              readAndGetRequest(p)
            }
          } catch {
            case t: Throwable =>
              shutdown()
              p.tryFailure(t)
          }
        }

      case Failure(e) =>
        lock.synchronized { shutdown() }
        p.tryFailure(e)
    }(Execution.trampoline)
  }


  // WARNING: may return `null` for performance reasons.
  // WARNING: must be called from within a `lock.synchronized` block
  private[this] def maybeGetRequest(): HttpRequest = {
      if (parser.parsePrelude(buffered)) {
        val prelude = parser.getRequestPrelude()
        val body = getBody()
        HttpRequest(prelude.method, prelude.uri, prelude.majorVersion, prelude.minorVersion, prelude.headers.toSeq, body)
      } else {
        null
      }
  }

  def shutdown(): Unit = lock.synchronized {
    parser.shutdownParser()
  }

  // Body writers ///////////////////

  private class StaticBodyWriter(forceClose: Boolean, sb: StringBuilder, len: Long) extends InternalWriter {
    private var cache = new ArrayBuffer[ByteBuffer](3)
    private var cachedBytes: Int = _
    private var closed = false
    private var written: Long = 0L

    { // constructor business
      sb.append("content-length: ").append(len).append("\r\n\r\n")
      val prelude = StandardCharsets.ISO_8859_1.encode(CharBuffer.wrap(sb))
      cache += prelude
      cachedBytes = prelude.remaining()
    }

    override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else {
        logger.debug(s"StaticBodyWriter: write: $buffer")
        val bufSize = buffer.remaining()

        if (bufSize == 0) InternalWriter.cachedSuccess
        else if (written + bufSize > len) {
          // need to truncate and log an error.
          val nextSize = len - written
          written = len
          buffer.limit(buffer.position() + nextSize.toInt)
          if (buffer.hasRemaining) {
            cache += buffer
            cachedBytes += buffer.remaining()
          }
          logger.error(
            s"StaticBodyWriter: Body overflow detected. Expected bytes: $len, attempted " +
              s"to send: ${written + bufSize}. Truncating."
          )

          InternalWriter.cachedSuccess
        }
        else if (cache.isEmpty && bufSize > InternalWriter.bufferLimit) {
          // just write the buffer if it alone fills the cache
          assert(cachedBytes == 0, "Invalid cached bytes state")
          written += bufSize
          pipeline.channelWrite(buffer)
        }
        else {
          cache += buffer
          written += bufSize
          cachedBytes += bufSize

          if (cachedBytes > InternalWriter.bufferLimit) flush()
          else InternalWriter.cachedSuccess
        }
      }
    }

    override def flush(): Future[Unit] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else {
        logger.debug("StaticBodyWriter: Channel flushed")
        if (cache.nonEmpty) {
          val buffs = cache
          cache = new ArrayBuffer[ByteBuffer](math.min(16, buffs.length + 2))
          cachedBytes = 0
          pipeline.channelWrite(buffs)
        }
        else InternalWriter.cachedSuccess
      }
    }

    override def close(): Future[RouteResult] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else {
        logger.debug("closed")
        if (cache.nonEmpty) flush().map( _ => lock.synchronized {
          closed = true
          selectComplete(forceClose)
        })(Execution.directec)
        else {
          closed = true
          Future.successful(selectComplete(forceClose))
        }
      }
    }
  }

  // Write data as chunks
  private class ChunkedBodyWriter(
      forceClose: Boolean,
      private var prelude: StringBuilder,
      maxCacheSize: Int
  ) extends InternalWriter {
    prelude.append("transfer-encoding: chunked\r\n")

    private val cache = new ListBuffer[ByteBuffer]

    private var cacheSize = 0
    private var closed = false

    override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else if (!buffer.hasRemaining) InternalWriter.cachedSuccess
      else {
        cache += buffer
        cacheSize += buffer.remaining()

        if (cacheSize > maxCacheSize) flush()
        else InternalWriter.cachedSuccess
      }
    }

    override def flush(): Future[Unit] = flushCache(false)

    private def flushCache(last: Boolean): Future[Unit] = lock.synchronized {
      if (closed)  InternalWriter.closedChannelException
      else {
        if (last) {
          closed = true
          cache += ByteBuffer.wrap(terminationBytes)
        }

        var buffers = cache.result()
        cache.clear()

        if (cacheSize > 0) {
          buffers = lengthBuffer()::buffers
          cacheSize = 0
        }

        if (prelude != null) {
          val buffer = ByteBuffer.wrap(prelude.result().getBytes(StandardCharsets.ISO_8859_1))
          prelude = null
          buffers = buffer::buffers
        }

        if (buffers.isEmpty) InternalWriter.cachedSuccess
        else pipeline.channelWrite(buffers)
      }
    }

    override def close(): Future[RouteResult] = lock.synchronized {
      if (closed)  InternalWriter.closedChannelException
      else {
        flushCache(true).map( _ => lock.synchronized {
          if (forceClose || !parser.contentComplete()) Close
          else {
            parser.reset()
            Reload
          }
        })(Execution.directec)
      }
    }

    private def lengthBuffer(): ByteBuffer = {
      val bytes = Integer.toHexString(cacheSize).getBytes(StandardCharsets.US_ASCII)
      val b = ByteBuffer.allocate(2 + bytes.length + 2)
      b.put(CRLFBytes).put(bytes).put(CRLFBytes).flip()
      b
    }
  }

  /** Dynamically select a [[BodyWriter]]
    *
    * This process writer buffers bytes until `InternalWriter.bufferLimit` is exceeded then
    * falls back to a [[ChunkedBodyWriter]]. If the buffer is not exceeded, the entire
    * body is written as a single chunk using standard encoding.
    */
  private class SelectingWriter(forceClose: Boolean, sb: StringBuilder) extends InternalWriter {
    private var closed = false
    private val cache = new ListBuffer[ByteBuffer]
    private var cacheSize = 0

    private var underlying: InternalWriter = null

    override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
      if (underlying != null) underlying.write(buffer)
      else if (closed) InternalWriter.closedChannelException
      else {
        cache += buffer
        cacheSize += buffer.remaining()

        if (cacheSize > InternalWriter.bufferLimit) {
          // Abort caching: too much data. Create a chunked writer.
          startChunked()
        }
        else InternalWriter.cachedSuccess
      }
    }

    override def flush(): Future[Unit] = lock.synchronized {
      if (underlying != null) underlying.flush()
      else if (closed) InternalWriter.closedChannelException
      else {
        // Gotta go with chunked encoding...
        startChunked().flatMap(_ => flush())(Execution.directec)
      }
    }

    override def close(): Future[HttpCodec.RouteResult] = lock.synchronized {
      if (underlying != null) underlying.close()
      else if (closed) InternalWriter.closedChannelException
      else {
        // write everything we have as a fixed length body
        closed = true
        val buffs = cache.result()
        cache.clear()
        sb.append("content-length: ").append(cacheSize).append("\r\n\r\n")
        val prelude = StandardCharsets.US_ASCII.encode(CharBuffer.wrap(sb))

        pipeline.channelWrite(prelude::buffs).map(_ => lock.synchronized {
          selectComplete(forceClose)
        })(Execution.directec)
      }
    }

    // start a chunked encoding writer and write the contents of the cache
    private[this] def startChunked(): Future[Unit] = {
      underlying = new ChunkedBodyWriter(false, sb, InternalWriter.bufferLimit)

      val buff = BufferTools.joinBuffers(cache)
      cache.clear()

      underlying.write(buff)
    }
  }

  // Upon completion of rendering the HTTP response we need to determine if we
  // are going to continue using this session (socket).
  private[this] def selectComplete(forceClose: Boolean): RouteResult = {
    // TODO: what should we do about the trailer headers?
    if (forceClose || !parser.contentComplete() || parser.inChunkedHeaders()) Close
    else {
      requestId += 1
      parser.reset()
      Reload
    }
  }
}

private object HttpCodec {
  private val logger = getLogger

  private val CRLFBytes = "\r\n".getBytes(StandardCharsets.US_ASCII)
  private val terminationBytes = "\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)

  sealed trait RouteResult
  case object Reload  extends RouteResult
  case object Close   extends RouteResult
}
