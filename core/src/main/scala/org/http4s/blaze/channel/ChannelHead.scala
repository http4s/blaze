package org.http4s.blaze.channel

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.HeadStage

trait ChannelHead extends HeadStage[ByteBuffer] {
  import ChannelHead.brokePipeMessages

  /** Close the channel with an error */
  protected def closeWithError(t: Throwable): Unit

  /** Close the channel under normal terms such as EOF */
  protected def closeChannel(): Unit

  override def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
    case Disconnect =>
      closeChannel()

    case Error(e) =>
      logger.error(e)(s"$name received unhandled error command")
      closeChannel()
      sendInboundCommand(UnhandledError(e))

    case cmd => logger.warn(s"Unhandled outbound command: $cmd")
  }

  protected def checkError(e: Throwable): Throwable = e match {
    case EOF =>
      logger.warn("Unhandled EOF")
      EOF

    case e: ClosedChannelException =>
      logger.debug("Channel closed, dropping packet")
      EOF

    case e: IOException if brokePipeMessages.contains(e.getMessage) =>
      closeChannel()
      EOF

    case e: IOException =>
      logger.warn(e)("Channel IOException not known to be a disconnect error")
      closeChannel()
      EOF

    case e: Throwable =>  // Don't know what to do besides close
      channelError(e)
      e
  }

  private def channelError(e: Throwable) {
    logger.error(e)("Unexpected fatal error")
    sendInboundCommand(Error(e))
    closeWithError(e)
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
