package blaze.pipeline.stages.http.websocket

import blaze.pipeline.stages.ByteToObjectStage
import java.nio.ByteBuffer

import WebsocketBits._
import scala.annotation.switch
import java.nio.charset.{StandardCharsets, Charset}

/**
 * @author Bryce Anderson
 *         Created on 1/15/14
 */
class WebSocketDecoder(isClient: Boolean, val maxBufferSize: Int = 0) extends ByteToObjectStage[WebSocMessage] {

  val name = "Websocket Decoder"

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  def messageToBuffer(in: WebSocMessage): Seq[ByteBuffer] = ???

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  def bufferToMessage(in: ByteBuffer): Option[WebSocMessage] = {

    if (in.remaining() < 2) return None

    val len = getMsgLength(in)
    if (len < 0) return None

    val opcode = (in.get(0) & OP_CODE) >> 4
    val finished = (in.get(0) & FINISHED) != 0
    val masked = (in.get(1) & MASK) != 0

    if (masked && isClient) decodeError("Client received a masked message")
    val m = if (masked) getMask(in) else null
    
    val bodyOffset = lengthOffset(in) + (if (masked) 4 else 0)
    val oldLim = in.limit()
    val bodylen = bodyLength(in)

    in.position(bodyOffset)
    in.limit(in.position() + bodylen)

    val slice = in.slice()

    in.position(in.limit())
    in.limit(oldLim)

    val result = (opcode: @switch) match {
    case CONTINUATION => Continuation(decodeBinary(slice, m), finished)
    case TEXT =>  TextMessage(decodeText(slice, m), finished)
    case BINARY => BinaryMessage(decodeBinary(slice, m), finished)
    case CLOSE => Close(decodeBinary(slice, m), finished)
    case PING => Ping(decodeBinary(slice, m), finished)
    case PONG => Pong(decodeBinary(slice, m), finished)
    case _ => decodeError(s"Unknown message type: " + Integer.toHexString(opcode))

    }
    Some(result)
  }

  private def decodeError(msg: String): Nothing = sys.error(msg)

  private def decodeText(in: ByteBuffer, mask: Array[Byte]): String = {
    new String(decodeBinary(in, mask), StandardCharsets.UTF_8)
  }

  private def decodeBinary(in: ByteBuffer, mask: Array[Byte]): Array[Byte] = {

    val data = new Array[Byte](in.remaining())
    in.get(data)

    if (mask == null) {  // We can use the charset decode
      var i = 0
      while (i < data.length) {
        data(i) = (data(i) ^ mask(i & 0x3)).toByte   // i mod 4 is the same as i & 0x3 but slower
        i += 1
      }
    }
    data
  }

  private def lengthOffset(in: ByteBuffer): Int = {
    val len = (in.get(1) & LENGTH) >> 1

    val offset = if (len < 126) 2
    else if (len == 126) 4
    else if (len == 127) 10
    else decodeError("Length error!")

    offset
  }

  private def getMask(in: ByteBuffer): Array[Byte] = {
    val m = new Array[Byte](4)
    in.mark()
    in.position(lengthOffset(in))
    in.get(m)
    in.reset()
    m
  }

  private def bodyLength(in: ByteBuffer): Int = {
    val len = (in.get(1) & LENGTH) >> 1
    if (len < 126) len
    else if (len == 126) in.getShort(2)
    else if (len == 127) {
      val l = in.getLong(2)
      if (l > Int.MaxValue) decodeError("Frame is too long")
      else l.toInt
    }
    else decodeError("Length error")
  }

  private def getMsgLength(in: ByteBuffer): Int = {
    var totalLen = if ((in.get(0) & MASK) != 0) 6 else 2
    val len = (in.get(1) & LENGTH) >> 1

    if (len == 126) totalLen += 2
    if (len == 127) totalLen += 8

    if (in.remaining() < totalLen) return -1

    totalLen += bodyLength(in)

    if (in.remaining() < totalLen) -1
    else totalLen
  }
}

