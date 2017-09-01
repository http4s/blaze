package org.http4s.blaze.pipeline.stages.monitors

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import org.http4s.blaze.channel.BufferPipelineBuilder
import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.util.Execution.directec

import scala.concurrent.Future


/** Facilitates injecting some monitoring tools into the pipeline */
abstract class ConnectionMonitor {

  def wrapBuilder(factory: BufferPipelineBuilder): BufferPipelineBuilder =
    factory.andThen(_.prepend(new ServerStatusStage))
  
  protected def connectionAccepted(): Unit
  protected def connectionClosed(): Unit

  protected def bytesInbound(n: Long): Unit
  protected def bytesOutBound(n: Long): Unit

  private class ServerStatusStage extends MidStage[ByteBuffer, ByteBuffer] {
    val name = "ServerStatusStage"

    private val cleaned = new AtomicBoolean(false)

    private def clearCount() = if (!cleaned.getAndSet(true)) {
      connectionClosed()
    }

    override def stageStartup(): Unit = {
      connectionAccepted()
      super.stageStartup()
    }

    override def stageShutdown(): Unit = {
      clearCount()
      cleaned.set(true)
      super.stageShutdown()
    }

    override def finalize(): Unit = {
      clearCount()
      super.finalize()
    }

    def writeRequest(data: ByteBuffer): Future[Unit] = {
      bytesOutBound(data.remaining.toLong)
      channelWrite(data)
    }

    override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
      bytesOutBound(data.foldLeft(0)((i, b) => i + b.remaining()).toLong)
      channelWrite(data)
    }

    def readRequest(size: Int): Future[ByteBuffer] =
      channelRead(size).map { b => bytesInbound(b.remaining.toLong); b}(directec)
  }
}


