package org.http4s.blaze.channel

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.HeadStage

trait ChannelHead extends HeadStage[ByteBuffer] {
  import ChannelHead.brokePipeMessages

  /** Close the channel with an error
    * __NOTE:__ EOF is a valid error to close the channel with and signals normal termination.
    * This method should __not__ send a [[Disconnected]] command. */
  protected def closeWithError(t: Throwable): Unit

  override def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
      case Disconnect => closeWithError(EOF)
      case Error(e)   => closeWithError(e)
      case cmd        => // NOOP
  }

  /** Filter the error, replacing known "EOF" like errors with EOF */
  protected def checkError(e: Throwable): Throwable = e match {
    case EOF => EOF
    case e: ClosedChannelException => EOF
    case e: IOException if brokePipeMessages.contains(e.getMessage) => EOF
    case e: IOException =>
      logger.warn(e)("Channel IOException not known to be a disconnect error")
      EOF

    case e => e
  }
}

object ChannelHead {

  // If the connection is forcibly closed, we might get an IOException with one of the following messages
  private [blaze] val brokePipeMessages = Seq(
    "Connection reset by peer",   // Found on Linux
    "An existing connection was forcibly closed by the remote host",    // Found on windows
    "Broken pipe"   // also found on Linux
  )
}
