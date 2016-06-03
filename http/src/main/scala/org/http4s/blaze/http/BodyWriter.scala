package org.http4s.blaze.http

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/** Output pipe for writing http responses */
abstract class BodyWriter private[http] {

  /** Write a message to the pipeline
    *
    * Write an entire message will be written to the channel. This buffer may not be written
    * to the wire and instead buffered. Buffers can be manually flushed with the `flush` method
    * or by closing the [[BodyWriter]].
    *
    * @param buffer `ByteBuffer` to write to the channel
    * @return a `Future[Unit]` which resolves upon completion. Errors are handled through the `Future`.
    */
  def write(buffer: ByteBuffer): Future[Unit]

  /** Flush any bytes to the pipeline
    *
    * This may be a NOOP depending on the nature of the[[BodyWriter]].
    *
    * @return a `Future[Unit]` that resolves when any buffers have been flushed.
    */
  def flush(): Future[Unit]

  /** Close the writer and flush any buffers
    *
    * @return a `Future[Unit]` which will resolve once the close process has completed.
    */
  def close(): Future[Completed]
}

private object BodyWriter {
  val cachedSuccess = Future.successful(())
  def closedChannelException = Future.failed(new IOException("Channel closed"))
  val bufferLimit = 40*1024

  def selectWriter(prelude: HttpResponsePrelude, sb: StringBuilder, stage: HttpServerStage): BodyWriter = {
    val forceClose = false
    if (false) ???  // TODO: need to look through the headers and see if we are sending a length header.
    else new SelectingWriter(forceClose, sb, stage)
  }

  def renderHeaders(sb: StringBuilder, headers: Headers) {
    headers.foreach { case (k, v) =>
      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (!k.equalsIgnoreCase("Transfer-Encoding") && !k.equalsIgnoreCase("Content-Length")) {
        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v).append('\r').append('\n')
      }
    }
  }

  def selectComplete(forceClose: Boolean, stage: HttpServerStage): Completed =
    new Completed(
      if (forceClose || !stage.contentComplete()) HttpServerStage.Close
      else HttpServerStage.Reload
    )
}

/** Dynamically select a [[BodyWriter]]
  *
  * This process writer buffers bytes until `BodyWriter.bufferLimit` is exceeded then
  * falls back to a [[ChunkedBodyWriter]]. If the buffer is not exceeded, the entire
  * body is written as a single chunk using standard encoding.
  */
private class SelectingWriter(forceClose: Boolean, sb: StringBuilder, stage: HttpServerStage) extends BodyWriter {
  private var closed = false
  private val cache = new ListBuffer[ByteBuffer]
  private var cacheSize = 0

  // reuse the cache as our lock but make an alias
  private val lock = cache

  private var underlying: BodyWriter = null

  override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
    if (underlying != null) underlying.write(buffer)
    else if (closed) BodyWriter.closedChannelException
    else {
      cache += buffer
      cacheSize += buffer.remaining()

      if (cacheSize > BodyWriter.bufferLimit) {
        // Abort caching: too much data. Create a chunked writer.
        startChunked()
      }
      else BodyWriter.cachedSuccess
    }
  }

  override def flush(): Future[Unit] = lock.synchronized {
    if (underlying != null) underlying.flush()
    else if (closed) BodyWriter.closedChannelException
    else {
      // Gotta go with chunked encoding...
      startChunked().flatMap(_ => flush())(Execution.directec)
    }
  }

  override def close(): Future[Completed] = lock.synchronized {
    if (underlying != null) underlying.close()
    else if (closed) BodyWriter.closedChannelException
    else {
      // write everything we have as a fixed length body
      closed = true
      val buffs = cache.result(); cache.clear();
      val len = buffs.foldLeft(0)((acc, b) => acc + b.remaining())
      sb.append(s"Content-Length: $len\r\n\r\n")
      val prelude = StandardCharsets.US_ASCII.encode(sb.result())

      stage.channelWrite(prelude::buffs).map(_ => lock.synchronized {
        BodyWriter.selectComplete(forceClose, stage)
      })(Execution.directec)
    }
  }

  // start a chunked encoding writer and write the contents of the cache
  private def startChunked(): Future[Unit] = {
    // Gotta go with chunked encoding...
    sb.append("Transfer-Encoding: chunked\r\n\r\n")
    val prelude = ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.US_ASCII))
    underlying = new ChunkedBodyWriter(false, prelude, stage, BodyWriter.bufferLimit)

    val buff = BufferTools.joinBuffers(cache)
    cache.clear()

    underlying.write(buff)
  }
}
