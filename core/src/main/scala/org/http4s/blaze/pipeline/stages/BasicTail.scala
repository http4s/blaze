package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.TailStage
import org.log4s.getLogger

import scala.concurrent.{Future, Promise}

class BasicTail[T](val name: String) extends TailStage[T] {

  private[this] val log = getLogger
  private[this] val closeP = Promise[Unit]
  private[this] val startP = Promise[Unit]

  def onClose: Future[Unit] = closeP.future

  def started: Future[Unit] = startP.future

  def close(): Unit = sendOutboundCommand(Disconnect)

  override def inboundCommand(cmd: InboundCommand): Unit = cmd match {
    case Connected => startP.trySuccess(())
    case Disconnected | EOF => closeP.trySuccess(())
    case other =>
      val exc = new IllegalStateException(s"Unknown command: $other")
      log.warn(exc)("Unknown command")
      closeP.tryFailure(exc)
  }
}
