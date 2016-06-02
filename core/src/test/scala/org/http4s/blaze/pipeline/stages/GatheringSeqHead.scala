package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.pipeline.Command.OutboundCommand

import scala.concurrent.{Future, Promise}


class GatheringSeqHead[O](items: Seq[O]) extends SeqHead[O](items) {

  private var result: Option[Promise[Seq[O]]] = None

  /** Receives outbound commands
    * Override to capture commands. */
  override def outboundCommand(cmd: OutboundCommand): Unit = {
    if (cmd == Command.Disconnect) this.synchronized {
      result match {
        case None => sys.error("Invalid state!")
        case Some(p) =>
          p.success(this.results)
      }
    }

    super.outboundCommand(cmd)
  }

  def go(): Future[Seq[O]] = {
    val p = this.synchronized {
      assert(result.isEmpty, s"Cannot use ${this.getClass.getSimpleName} more than once")
      val p = Promise[Seq[O]]
      result = Some(p)

      sendInboundCommand(Command.Connected)
      p
    }

    p.future
  }

}
