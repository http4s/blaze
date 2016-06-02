package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.Execution

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

sealed trait BodyWriter {
  def write(buffer: ByteBuffer): Future[Unit]

  def flush(): Future[Unit]

  def close(): Future[Completed]
}

private object BodyWriter {
  val cachedSuccess = Future.successful(())

  def selectWriter(prelude: HttpResponsePrelude, sb: StringBuilder, stage: HttpServerStage): BodyWriter = {
    if (false) new CachingWriter(false, sb, stage)
    else {
      sb.append("Transfer-Encoding: chunked\r\n\r\n")
      new ChunkedWriter(false, ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.US_ASCII)), stage)
    }
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
}

// Write data in a chunked manner
private class ChunkedWriter(forceClose: Boolean, private var prelude: ByteBuffer, stage: HttpServerStage) extends BodyWriter {
  private val maxCacheSize = 20*1024

  private var cache = new ListBuffer[ByteBuffer]
  private var cacheSize = 0


  override def write(buffer: ByteBuffer): Future[Unit] = {
    if (buffer.hasRemaining) {
      cache += buffer
      cacheSize += buffer.remaining()
    }

    if (cacheSize > maxCacheSize) flush()
    else BodyWriter.cachedSuccess
  }

  override def flush(): Future[Unit] = {
    val buffs = {
      val cacheBuffs = if (cache.nonEmpty) {
        cache += ChunkedWriter.CRLFBuffer
        val buffs = writeLength::cache.result()
        cache.clear()
        cacheSize = 0
        buffs
      } else Nil

      if (prelude != null) {
        val p = prelude
        prelude = null
        p::cacheBuffs
      }
      else cacheBuffs
    }

    if (buffs.nonEmpty) stage.channelWrite(buffs)
    else BodyWriter.cachedSuccess
  }

  override def close(): Future[Completed] = {
    val f = if (cache.nonEmpty || prelude != null) flush().flatMap(_ => writeTermination())(Execution.directec)
            else writeTermination()

    f.map { _ =>
      new Completed(
        if (forceClose || !stage.contentComplete()) HttpServerStage.Close
        else HttpServerStage.Reload
      )
    }(Execution.directec)
  }

  private def writeTermination(): Future[Unit] = {
    val s = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
    stage.channelWrite(ByteBuffer.wrap(s))
  }

  private def writeLength: ByteBuffer = {
    val bytes = Integer.toHexString(cacheSize).getBytes(StandardCharsets.US_ASCII)
    val b = ByteBuffer.allocate(bytes.length + 2)
    b.put(bytes).put(ChunkedWriter.CRLFBytes).flip()
    b
  }
}

private object ChunkedWriter {
  private val CRLFBytes = "\r\n".getBytes(StandardCharsets.US_ASCII)
  private def CRLFBuffer = ByteBuffer.wrap(CRLFBytes)
}

private class CachingWriter(forceClose: Boolean, sb: StringBuilder, stage: HttpServerStage) extends BodyWriter {
  private var cache = new ListBuffer[ByteBuffer]
  private var cacheSize = 0L

  override def write(buffer: ByteBuffer): Future[Unit] = {
    if (buffer.hasRemaining) {
      cache += buffer
      cacheSize += buffer.remaining()
    }

    BodyWriter.cachedSuccess
  }

  // This writer doesn't flush
  override def flush(): Future[Unit] = BodyWriter.cachedSuccess

  override def close(): Future[Completed] = {
    sb.append("Content-Length: ").append(cacheSize).append("\r\n\r\n")
    val prelude = StandardCharsets.US_ASCII.encode(sb.result())

    val buffs = prelude::cache.result()

    stage.channelWrite(buffs).map(_ => new Completed({
      if (forceClose || !stage.contentComplete()) HttpServerStage.Close
      else HttpServerStage.Reload
    }))(Execution.directec)
  }
}