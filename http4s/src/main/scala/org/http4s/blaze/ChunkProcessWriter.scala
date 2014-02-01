package org.http4s.blaze

import java.nio.ByteBuffer
import blaze.pipeline.TailStage
import scala.concurrent.{Future, ExecutionContext}
import org.http4s.{TrailerChunk, BodyChunk}
import com.typesafe.scalalogging.slf4j.Logging
import java.nio.charset.Charset

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */
final class ChunkProcessWriter(private var buffer: ByteBuffer, pipe: TailStage[ByteBuffer])
                              (implicit val ec: ExecutionContext) extends ProcessWriter {

  import ChunkProcessWriter._

  private val CRLFBytes = "\r\n".getBytes(ASCII)

  private def CRLF = ByteBuffer.wrap(CRLFBytes).asReadOnlyBuffer()

  private val lengthBuffer = ByteBuffer.allocate(15)    // Should be enough

  private def writeLength(buffer: ByteBuffer, length: Int) {
    buffer.put(Integer.toHexString(length).getBytes(ASCII)).put(CRLFBytes)
  }

  protected def writeBodyChunk(chunk: BodyChunk, flush: Boolean): Future[Any] = {
    lengthBuffer.clear()
    writeLength(lengthBuffer, chunk.length)
    val c = ByteBuffer.wrap(chunk.toArray)

    val list = lengthBuffer::c::CRLF::Nil

    if (buffer != null) {
      val i = buffer
      buffer = null
      pipe.channelWrite(i::list)
    }
    else pipe.channelWrite(list)
  }


  protected def writeEnd(chunk: BodyChunk, t: Option[TrailerChunk]): Future[Any] = {
    writeBodyChunk(chunk, true)
  }
}

object ChunkProcessWriter {
  val ASCII = Charset.forName("US-ASCII")
}
