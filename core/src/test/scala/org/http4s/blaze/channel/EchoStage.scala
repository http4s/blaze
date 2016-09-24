package org.http4s.blaze
package channel

import org.http4s.blaze.pipeline.TailStage
import java.nio.ByteBuffer
import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success}
import org.http4s.blaze.pipeline.Command.EOF


class EchoStage extends TailStage[ByteBuffer] {

  def name: String = "EchoStage"

  val msg = "echo: ".getBytes

  private implicit def ec = util.Execution.trampoline

  final override def stageStartup(): Unit = {
    channelRead().onComplete{
      case Success(buff) =>
        val b = BufferTools.allocateHeap(buff.remaining() + msg.length)
        b.put(msg).put(buff).flip()

        // Write it, wait for conformation, and start again
        channelWrite(b).onSuccess{ case _ => stageStartup() }

      case Failure(EOF) => logger.debug("Channel closed.")
      case Failure(t) => logger.error(t)("Channel read failed")
    }
  }
}