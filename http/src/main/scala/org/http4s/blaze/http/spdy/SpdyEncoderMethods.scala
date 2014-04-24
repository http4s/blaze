package org.http4s.blaze.http.spdy

import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/29/14
 */
private[spdy] trait SpdyEncoderMethods {

  protected def deflater: SpdyHeaderEncoder
  protected def spdyVersion: Int

  def encodeData(frame: DataFrame): Seq[ByteBuffer] = {
    import frame._

      val header = ByteBuffer.allocate(8)

      header.putInt(streamid & Masks.STREAMID)
      header.put((if (isLast) Flags.FINISHED else 0).toByte)
      putLength(header, length)
      header.flip()

      header::data::Nil
  }

  def encodeSynStream(frame: SynStreamFrame): Seq[ByteBuffer] = {
    import frame._

    val arr = new Array[Byte](18)
    val buff = ByteBuffer.wrap(arr)

    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
    buff.putShort(frameID.toShort)

    var flags = 0
    if (unidirectional) flags |= Flags.UNIDIRECTIONAL
    if (isLast) flags |= Flags.FINISHED

    buff.put(flags.toByte)
    buff.position(8)
    buff.putInt(streamid)
    buff.putInt(associatedStream)
    buff.put((priority << 5).toByte)   // priority

    val hbuff = new SpdyHeaderEncoder().encodeHeaders(headers)

    putLength(buff, hbuff.remaining() + 10)

    buff.position(18)
    buff.flip()

    buff::hbuff::Nil
  }

  def encodeSynReply(frame: SynReplyFrame): Seq[ByteBuffer] = {
    import frame._


      val buff = ByteBuffer.allocate(12)

      buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
      buff.putShort(frameID.toShort)

      val flags = if (isLast) Flags.FINISHED else 0
      buff.put(flags.toByte)
      buff.position(8)
      buff.putInt(streamid & Masks.STREAMID)

      // Must deflate, and see how long we are
      val hbuff = deflater.encodeHeaders(headers)
      buff.position(5)
      putLength(buff, hbuff.remaining() + 4)

      buff.position(12)
      buff.flip()

      buff::hbuff::Nil
  }

  def encodeRstStream(frame: RstStreamFrame): Seq[ByteBuffer] = {
    import frame._

    val buff = ByteBuffer.allocate(16)
    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
        .putShort(frameID.toShort)
        .position(7)
    buff.put(8.toByte)
        .putInt(streamid & Masks.STREAMID)
        .putInt(code.id)
        .flip()

    buff::Nil
  }

  def encodeSettings(frame: SettingsFrame): Seq[ByteBuffer] = {
    import frame._

    val count = settings.length
    val buff = ByteBuffer.allocate(12 + 8 * count)

    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
      .putShort(frameID.toShort)
      .put(if (clear) 0x1.toByte else 0.toByte)

    putLength(buff, 8 * count + 4)
    buff.putInt(count)

    settings.foreach(_.encode(buff))
    buff.flip()

    buff::Nil
  }

  def encodePing(frame: PingFrame): Seq[ByteBuffer] = {
    import frame._

    val buff = ByteBuffer.allocate(12)

    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
      .putShort(frameID.toShort)
      .putInt(4)    // flags and length, which are 0 and 4 respectively
      .putInt(id)

    buff.flip()
    buff::Nil
  }

  def encodeGoAway(frame: GoAwayFrame): Seq[ByteBuffer] = {
    import frame._

    val buff = ByteBuffer.allocate(16)
    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
      .putShort(frameID.toShort)
      .putInt(8)    // flags and length, which are 0 and 8 respectively
      .putInt(lastGoodStream)
      .putInt(code.id)

    buff.flip()
    buff::Nil
  }

  def encodeHeaders(frame: HeadersFrame): Seq[ByteBuffer] = {
    import frame._

    val buff = ByteBuffer.allocate(12)
    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
      .putShort(frameID.toShort)
    if (isLast) buff.put(Flags.FINISHED)

    val hbuff = new SpdyHeaderEncoder().encodeHeaders(headers)
    buff.position(5)
    putLength(buff, hbuff.remaining() + 4)
      .putInt(streamid)
      .flip()

    buff::hbuff::Nil
  }

  def encodeWindowUpdate(frame: WindowUpdateFrame): Seq[ByteBuffer] = {
    import frame._

    val buff = ByteBuffer.allocate(16)
    buff.putShort((Flags.CONTROL << 8 | spdyVersion).toShort)
      .putShort(frameID.toShort)
      .putInt(8)    // flags (0) and length (8)
      .putInt(streamid & Masks.STREAMID)
      .putInt(delta)
      .flip()

    buff::Nil
  }









  private def putLength(buff: ByteBuffer, len: Int): ByteBuffer = {
    buff.position(5)
    buff.put((len >>> 16 & 0xff).toByte)
      .put((len >>> 8 & 0xff).toByte)
      .put((len & 0xff).toByte)

    buff
  }

}
