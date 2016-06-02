package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpServerStage.RouteResult
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/** Dynamically select a [[BodyWriter]]
  *
  * This process writer buffers bytes until `InternalWriter.bufferLimit` is exceeded then
  * falls back to a [[ChunkedBodyWriter]]. If the buffer is not exceeded, the entire
  * body is written as a single chunk using standard encoding.
  */
private class SelectingWriter(forceClose: Boolean, sb: StringBuilder, stage: HttpServerStage) extends InternalWriter {
  private var closed = false
  private val cache = new ListBuffer[ByteBuffer]
  private var cacheSize = 0

  // reuse the cache as our lock but make an alias
  private val lock = cache

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

  override def close(): Future[RouteResult] = lock.synchronized {
    if (underlying != null) underlying.close()
    else if (closed) InternalWriter.closedChannelException
    else {
      // write everything we have as a fixed length body
      closed = true
      val buffs = cache.result(); cache.clear();
      val len = buffs.foldLeft(0)((acc, b) => acc + b.remaining())
      sb.append(s"Content-Length: $len\r\n\r\n")
      val prelude = StandardCharsets.US_ASCII.encode(sb.result())

      stage.channelWrite(prelude::buffs).map(_ => lock.synchronized {
        InternalWriter.selectComplete(forceClose, stage)
      })(Execution.directec)
    }
  }

  // start a chunked encoding writer and write the contents of the cache
  private def startChunked(): Future[Unit] = {
    // Gotta go with chunked encoding...
    sb.append("Transfer-Encoding: chunked\r\n\r\n")
    val prelude = ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.US_ASCII))
    underlying = new ChunkedBodyWriter(false, prelude, stage, InternalWriter.bufferLimit)

    val buff = BufferTools.joinBuffers(cache)
    cache.clear()

    underlying.write(buff)
  }
}