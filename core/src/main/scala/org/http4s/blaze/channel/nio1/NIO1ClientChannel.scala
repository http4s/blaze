/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.{ChannelOptions, OptionValue}

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectableChannel, SocketChannel}
import java.util.concurrent.atomic.AtomicBoolean

private[blaze] final class NIO1ClientChannel(
    private[this] val underlying: SocketChannel,
    private[this] val onClose: () => Unit)
    extends NIO1Channel {

  private[this] val closed = new AtomicBoolean(false)

  override val selectableChannel: SelectableChannel = underlying

  def configureBlocking(block: Boolean): Unit = {
    underlying.configureBlocking(block)
    ()
  }

  def getRemoteAddress: SocketAddress =
    underlying.getRemoteAddress

  def getLocalAddress: SocketAddress =
    underlying.getLocalAddress

  def configureOptions(options: ChannelOptions): Unit =
    options.options.foreach { case OptionValue(k, v) =>
      underlying.setOption(k, v)
    }

  def read(dst: ByteBuffer): Int =
    underlying.read(dst)

  def write(src: ByteBuffer): Int =
    underlying.write(src)

  def write(srcs: Array[ByteBuffer]): Long =
    underlying.write(srcs)

  def isOpen: Boolean =
    underlying.isOpen

  override def close(): Unit =
    try underlying.close()
    finally
      if (closed.compareAndSet(false, true)) {
        onClose()
      }

}
