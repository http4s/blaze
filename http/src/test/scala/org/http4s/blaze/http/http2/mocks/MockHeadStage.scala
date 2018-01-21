package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.{Command, HeadStage}
import org.http4s.blaze.util.BufferTools

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

private[http2] class MockHeadStage extends HeadStage[ByteBuffer] {
  override def name: String = "Head"

  val reads = new mutable.Queue[Promise[ByteBuffer]]()
  val writes = new mutable.Queue[(ByteBuffer, Promise[Unit])]()

  var disconnected: Boolean = false

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
    // We need to take all the writes and then clear since completing the
    // promises might trigger more writes
    val writePairs = writes.toList
    writes.clear()

    val buf = BufferTools.joinBuffers(writePairs.map { case (b, p) =>
      p.success(())
      b
    })
    buf
  }

  /** Receives outbound commands
    * Override to capture commands. */
  override def outboundCommand(cmd: OutboundCommand): Unit = {
    disconnected = disconnected | (cmd == Command.Disconnect)
    super.outboundCommand(cmd)
  }
}
