package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.http.HttpServerStage.RouteResult
import org.http4s.blaze.util.Execution

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import org.log4s.getLogger

private object StaticBodyWriter {
  val logger = getLogger
}

private class StaticBodyWriter(forceClose: Boolean, len: Long, stage: HttpServerStage) extends InternalWriter {
  private var closed = false
  private var written = 0L
  private val cache = new ArrayBuffer[ByteBuffer](3)
  private var cachedBytes = 0

  // Just reuse the cache as our lock but give it a good name
  private val lock = cache

  override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
    if (closed) BodyWriter.closedChannelException
    else {
      StaticBodyWriter.logger.debug(s"Channel write: $buffer")
      val bufSize = buffer.remaining()
      if (bufSize == 0) BodyWriter.cachedSuccess
      else if (written + bufSize > len) {
        // need to truncate and log an error.
        val nextSize = len - written
        written = len
        buffer.limit(buffer.position() + nextSize.toInt)
        if (buffer.hasRemaining) {
          cache += buffer
          cachedBytes += buffer.remaining()
        }
        StaticBodyWriter.logger.error(
          s"Body overflow detected. Expected bytes: $len, attempted " +
          s"to send: ${written + bufSize}. Truncating."
        )

        BodyWriter.cachedSuccess
      }
      else if (cache.isEmpty && bufSize > BodyWriter.bufferLimit) {
        // just write the buffer if it alone fills the cache
        assert(cachedBytes == 0, "Invalid cached bytes state")
        written += bufSize
        stage.channelWrite(buffer)
      }
      else {
        cache += buffer
        written += bufSize
        cachedBytes += bufSize

        if (cachedBytes > BodyWriter.bufferLimit) flush()
        else BodyWriter.cachedSuccess
      }
    }
  }

  override def flush(): Future[Unit] = lock.synchronized {
    if (closed) BodyWriter.closedChannelException
    else {
      StaticBodyWriter.logger.debug("Channel flushed")
      if (cache.nonEmpty) {
        val buffs = cache.result()
        cache.clear()
        cachedBytes = 0
        stage.channelWrite(buffs)
      }
      else BodyWriter.cachedSuccess
    }
  }

  override def close(): Future[RouteResult] = lock.synchronized {
    if (closed) BodyWriter.closedChannelException
    else {
      StaticBodyWriter.logger.debug("Channel closed")
      if (cache.nonEmpty) flush().map( _ => lock.synchronized {
        closed = true
        BodyWriter.selectComplete(forceClose, stage)
        })(Execution.directec)
      else {
        closed = true
        Future.successful(BodyWriter.selectComplete(forceClose, stage))
      }
    }
  }
}
