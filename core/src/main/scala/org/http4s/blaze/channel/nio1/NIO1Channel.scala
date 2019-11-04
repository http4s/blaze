package org.http4s.blaze.channel.nio1

import java.nio.channels.SelectableChannel

/** Facade over NIO1 `SelectableChannel`s
  *
  * The interface adds a way to cleanup resources other than the channel itself.
  */
trait NIO1Channel {
  /** The underlying java `SelectableChannel` */
  val selectableChannel: SelectableChannel

  /** Close this channel, releasing associated resources */
  def close(): Unit
}

object NIO1Channel {
  // Simple proxy implementation
  private[this] final class Impl(val selectableChannel: SelectableChannel) extends NIO1Channel {
    override def close(): Unit = selectableChannel.close()
  }

  /** Construct a basic `NIO1Channel` from any `SelectableChannel` */
  def apply(selectableChannel: SelectableChannel): NIO1Channel =
    new Impl(selectableChannel)
}
