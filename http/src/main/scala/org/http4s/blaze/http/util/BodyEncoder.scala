package org.http4s.blaze.http.util

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.{Body, BodyCommand, Headers}
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.Execution

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}

object BodyEncoder {
  def autoEncoder(headers: Headers, body: Body, maxBuffer: Int): Future[BodyEncoder] = {
    // right now we just acc all the buffers
    accBuffers(body).map(new StaticEncoder(_))(Execution.directec)
  }

  private def accBuffers(body: Body): Future[List[ByteBuffer]] = {
    val res = Promise[List[ByteBuffer]]

    def go(buffers: ListBuffer[ByteBuffer]): Future[List[ByteBuffer]] = body(BodyCommand.Next).flatMap { chunk =>
      if (chunk.hasRemaining) go(buffers += chunk)
      else Future.successful(buffers.result())
    }(Execution.trampoline)

    go(new ListBuffer[ByteBuffer])
  }
}

trait BodyEncoder {
  def writeBody(stage: TailStage[ByteBuffer], headers: StringBuilder, body: Body): Future[Unit]
}

/** This encoder already has all its data and assumes the rest of the body is empty */
private class StaticEncoder(body: List[ByteBuffer]) extends BodyEncoder {

  override def writeBody(stage: TailStage[ByteBuffer], headers: StringBuilder, _junk: Body): Future[Unit] = {
    val len = body.foldLeft(0L){ (acc, n) => acc + n.remaining() }
    val hsStr = headers.append("Content-Length: ").append(len).append("\r\n\r\n").result()
    val buffers = StandardCharsets.US_ASCII.encode(hsStr) +: body

    stage.channelWrite(buffers)
  }
}