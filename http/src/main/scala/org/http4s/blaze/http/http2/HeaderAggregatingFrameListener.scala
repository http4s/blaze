package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.Headers
import org.http4s.blaze.util.BufferTools
import Http2Exception.PROTOCOL_ERROR

/** A [[Http2FrameListener]] that decodes raw HEADERS, PUSH_PROMISE,
  * and CONTINUATION frames from ByteBuffer packets to a complete
  * collections of headers.
  *
  * @note This class is not 'thread safe' and should be treated accordingly
  */
private[http2] abstract class HeaderAggregatingFrameListener(
    inboundSettings: Http2Settings,
    headerDecoder: HeaderDecoder)
  extends Http2FrameListener {

  private[this] sealed trait PartialFrame {
    def streamId: Int
    var buffer: ByteBuffer
  }

  private[this] case class PHeaders(
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      var buffer: ByteBuffer) extends PartialFrame

  private[this] case class PPromise(
      streamId: Int,
      promisedId: Int,
      var buffer: ByteBuffer) extends PartialFrame

  private[this] var hInfo: PartialFrame = null


  ///////////////////////////////////////////////////////////////////////////

  /** Called on the successful receipt of a complete HEADERS block
    *
    * @param streamId stream id of the HEADERS block. The codec will never pass 0.
    * @param priority optional priority information associated with this HEADERS block.
    * @param endStream this is the last inbound frame for this stream.
    * @param headers decompressed headers.
    */
  def onCompleteHeadersFrame(streamId: Int,
                             priority: Priority,
                             endStream: Boolean,
                             headers: Headers): Http2Result

  /** Called on the successful receipt of a complete PUSH_PROMISE block
    *
    * @param streamId stream id of the associated stream. The codec will never pass 0.
    * @param promisedId promised stream id. This must be a valid, idle stream id. The codec will never pass 0.
    * @param headers decompressed headers.
    */
  def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result

  ////////////////////////////////////////////////////////////////////////////

  final def setMaxHeaderTableSize(maxSize: Int): Unit = { headerDecoder.setMaxHeaderTableSize(maxSize) }

  final override def inHeaderSequence: Boolean = hInfo != null

  final override def onHeadersFrame(streamId: Int,
                                    priority: Priority,
                                    endHeaders: Boolean,
                                    endStream: Boolean,
                                    buffer: ByteBuffer): Http2Result = {

    if (inHeaderSequence) {
      Error(PROTOCOL_ERROR.goaway(s"Received HEADERS frame while in in headers sequence. Stream $streamId"))
    } else if (buffer.remaining() > inboundSettings.maxHeaderListSize) {
      // TODO: this can legally be handled with a 431 response, but the headers must be processed to keep
      //       the header decompressor in a valid state. https://tools.ietf.org/html/rfc7540#section-10.5.1
      headerSizeError(buffer.remaining(), streamId)
    } else if (endHeaders) {
      val r = headerDecoder.decode(buffer, streamId, true)
      if (!r.success) r
      else {
        val hs = headerDecoder.finish()
        onCompleteHeadersFrame(streamId, priority, endStream, hs)
      }
    } else {
      hInfo = PHeaders(streamId, priority, endStream, buffer)
      Continue
    }
  }

  final override def onPushPromiseFrame(streamId: Int,
                                        promisedId: Int,
                                        endHeaders: Boolean,
                                        buffer: ByteBuffer): Http2Result = {

    if (inHeaderSequence) {
      val msg = "Received HEADERS frame while in in headers sequence"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else if (buffer.remaining() > inboundSettings.maxHeaderListSize) {
      // TODO: this can legally be handled with a 431 response, but the headers must be processed to keep
      //       the header decompressor in a valid state. https://tools.ietf.org/html/rfc7540#section-10.5.1
      headerSizeError(buffer.remaining(), streamId)
    } else if (endHeaders) {
      val r = headerDecoder.decode(buffer, streamId, true)
      if (!r.success) r
      else {
        val hs = headerDecoder.finish()
        onCompletePushPromiseFrame(streamId, promisedId, hs)
      }
    } else {
      hInfo = PPromise(streamId, promisedId, buffer)
      Continue
    }
  }

  final override def onContinuationFrame(streamId: Int, endHeaders: Boolean, buffer: ByteBuffer): Http2Result = {
    if (!inHeaderSequence) {
      val msg = s"Invalid CONTINUATION frame: not in partial header frame. Stream $streamId"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else if (hInfo.streamId != streamId) {
      val msg = s"Invalid CONTINUATION frame: stream Id's don't match. " +
        s"Expected ${hInfo.streamId}, received $streamId"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else {
      val totalSize = buffer.remaining() + hInfo.buffer.remaining()

      if (totalSize > inboundSettings.maxHeaderListSize) {
        // TODO: this can legally be handled with a 431 response, but the headers must be processed to keep
        // TODO: the header decompressor in a valid state. https://tools.ietf.org/html/rfc7540#section-10.5.1
        headerSizeError(totalSize, streamId)
      } else {
        val newBuffer = BufferTools.concatBuffers(hInfo.buffer, buffer)

        if (endHeaders) {
          val r = headerDecoder.decode(newBuffer, streamId, true)
          if (!r.success) r
          else {
            val hs = headerDecoder.finish()
            val i = hInfo // drop the reference before doing the stateful action
            hInfo = null

            i match {
              case PHeaders(sid, pri, es, _) => onCompleteHeadersFrame(sid, pri, es, hs)
              case PPromise(sid, pro, _) => onCompletePushPromiseFrame(sid, pro, hs)
            }
          }
        } else {
          hInfo.buffer = newBuffer
          Continue
        }
      }
    }
  }

  private def headerSizeError(size: Int, stream: Int): Error = {
    val msg = s"Stream($stream) sent too large of a header block. Received: $size. Limit: ${inboundSettings.maxHeaderListSize}"
    Error(PROTOCOL_ERROR.goaway(msg))
  }
}
