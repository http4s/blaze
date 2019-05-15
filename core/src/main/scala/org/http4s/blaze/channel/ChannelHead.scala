package org.http4s.blaze.channel

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.HeadStage

private abstract class ChannelHead extends HeadStage[ByteBuffer] {
  import ChannelHead.brokePipeMessages

  /** Filter the error, replacing known "EOF" like errors with EOF */
  protected def checkError(e: Throwable): Throwable = e match {
    case EOF => EOF
    case _: ClosedChannelException => EOF
    case e: IOException if brokePipeMessages.contains(e.getMessage) => EOF
    case e: IOException =>
      logger.warn(e)("Channel IOException not known to be a disconnect error")
      EOF

    case e => e
  }
}

object ChannelHead {

  // If the connection is forcibly closed, we might get an IOException with one of the following messages
  private[blaze] val brokePipeMessages = Set(
    "Connection timed out", // Found on Linux NIO1
    "Connection reset by peer", // Found on Linux
    "An existing connection was forcibly closed by the remote host", // Found on Windows
    "Broken pipe", // also found on Linux
    "The specified network name is no longer available.\r\n" // Found on Windows NIO2
  )
}
