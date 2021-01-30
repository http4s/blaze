package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.{ChannelOptions, OptionValue}

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectableChannel, SocketChannel}
import java.util.concurrent.atomic.AtomicBoolean

private[blaze] final class NIO1ClientChannel(private[this] val underlying: SocketChannel, private[this] val onClose: () => Unit) extends NIO1Channel {

  private[this] val closed = new AtomicBoolean(false)

  override val selectableChannel: SelectableChannel = underlying

  def configureBlocking(block: Boolean): Unit =
    underlying.configureBlocking(block)

  def getRemoteAddress: SocketAddress =
    underlying.getRemoteAddress

  def configureOptions(options: ChannelOptions): Unit =
    options.options.foreach { case OptionValue(k, v) =>
      underlying.setOption(k, v)
    }

  def read(dst: ByteBuffer): Int =
    underlying.read(dst)

  def write(src: ByteBuffer): Int =
    underlying.write(src)

  def close(): Unit =
    try {
      underlying.close()
    } finally {
      if (closed.compareAndSet(false, true)) {
        onClose()
      }
    }

}
