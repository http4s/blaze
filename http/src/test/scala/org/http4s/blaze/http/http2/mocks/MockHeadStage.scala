package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.util.BufferTools

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

private[http2] class MockHeadStage extends HeadStage[ByteBuffer] {
  override def name: String = "Head"

  val reads = new mutable.Queue[Promise[ByteBuffer]]()
  val writes = new mutable.Queue[(ByteBuffer, Promise[Unit])]()

  override def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]
    reads += p
    p.future
  }

  override def writeRequest(data: ByteBuffer): Future[Unit] = {
    val p = Promise[Unit]
    writes += data -> p
    p.future
  }

  def consumeOutboundData(): ByteBuffer = {
    val buf = BufferTools.joinBuffers(writes.toList.map { case (b, p) =>
      p.success(())
      b
    })
    writes.clear()
    buf
  }
}
