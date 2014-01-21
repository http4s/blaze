package blaze.channel.nio1

import scala.util.Try
import java.nio.ByteBuffer
import java.nio.channels.SelectableChannel

/**
 * @author Bryce Anderson
 *         Created on 1/21/14
 */

trait ChannelOps {
  def performRead(scratch: ByteBuffer): Try[ByteBuffer]

  def performWrite(buffer: Array[ByteBuffer]): Try[Any]

  def ch: SelectableChannel
}