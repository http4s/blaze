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

package org.http4s.blaze.core.util

import cats.effect._
import fs2._
import org.http4s.blaze.pipeline.TailStage
import org.http4s.util.StringWriter
import org.log4s.getLogger

import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.Future

private[blaze] class IdentityWriter[F[_]](size: Long, out: TailStage[ByteBuffer])(implicit
    protected val F: Async[F]
) extends Http1Writer[F] {

  private[this] var headers: ByteBuffer = null

  private var bodyBytesWritten = 0L

  private def willOverflow(count: Int) =
    if (size < 0L) false
    else count.toLong + bodyBytesWritten > size

  def writeHeaders(headerWriter: StringWriter): Future[Unit] = {
    headers = Http1Writer.headersToByteBuffer(headerWriter.result)
    FutureUnit
  }

  protected def writeBodyChunk(chunk: Chunk[Byte], flush: Boolean): Future[Unit] =
    if (willOverflow(chunk.size)) {
      // never write past what we have promised using the Content-Length header
      val msg =
        s"Will not write more bytes than what was indicated by the Content-Length header ($size)"

      IdentityWriter.logger.warn(msg)

      val reducedChunk = chunk.take((size - bodyBytesWritten).toInt)
      writeBodyChunk(reducedChunk, flush = true).flatMap(_ =>
        Future.failed(new IllegalArgumentException(msg))
      )(parasitic)
    } else {
      val b = chunk.toByteBuffer

      bodyBytesWritten += b.remaining

      if (headers != null) {
        val h = headers
        headers = null
        out.channelWrite(h :: b :: Nil)
      } else out.channelWrite(b)
    }

  protected def writeEnd(chunk: Chunk[Byte]): Future[Boolean] = {
    val total = bodyBytesWritten + chunk.size

    if (size < 0 || total >= size)
      writeBodyChunk(chunk, flush = true).map(Function.const(size < 0))(
        parasitic
      ) // require close if infinite
    else {
      val msg = s"Expected `Content-Length: $size` bytes, but only $total were written."

      IdentityWriter.logger.warn(msg)

      writeBodyChunk(chunk, flush = true).flatMap(_ =>
        Future.failed(new IllegalStateException(msg))
      )(parasitic)
    }
  }
}

private[blaze] object IdentityWriter {
  private lazy val logger = getLogger
}
