package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.{Command, MidStage}
import org.http4s.blaze.util.Execution.directec

import scala.concurrent.Future
import scala.collection.immutable.VectorBuilder
import org.http4s.blaze.pipeline.Command.Command

/**
 * @author Bryce Anderson
 *         Created on 1/7/14
 */
abstract class BufferingStage[T](bufferSize: Int, val name: String = "BufferingStage")
                                                               extends MidStage[T, T] {

  private val buffer = new VectorBuilder[T]
  private var size = 0

  protected def measure(buffer: T): Int

  // Just forward read requests
  def readRequest(size: Int): Future[T] = channelRead(size)

  def writeRequest(data: T): Future[Any] = {

    val dsize = measure(data)
    buffer += data

    if (dsize + size >= bufferSize) flush()
    else {
      size = size + dsize
      Future.successful()
    }
  }

  private def flush(): Future[Any] = {
    val f = writeRequest(buffer.result)
    buffer.clear()
    size = 0
    f
  }

  override protected def stageShutdown(): Unit = {
    buffer.clear()
    size = 0
    super.stageShutdown()
  }

  override def outboundCommand(cmd: Command): Unit = {
    cmd match {
      case Command.Flush => flush().onComplete(_ => super.outboundCommand(cmd))(directec)
      case cmd => super.outboundCommand(cmd)
    }
  }
}
