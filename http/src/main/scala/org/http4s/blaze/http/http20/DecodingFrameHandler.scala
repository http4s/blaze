package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.Headers
import org.http4s.blaze.util.BufferTools
import Http2Exception.PROTOCOL_ERROR

/** A [[FrameHandler]] that decodes raw HEADERS, PUSH_PROMISE,
  * and CONTINUATION frames from ByteBuffer packets to concrete
  * collections of headers.
  *
  * __Note:__This class is not 'thread safe' and should be treated accordingly
  */
abstract class DecodingFrameHandler extends FrameHandler {

  protected val headerDecoder: HeaderDecoder

  private sealed trait PartialFrame {
    def streamId: Int
    var buffer: ByteBuffer
  }

  private case class PHeaders(streamId: Int,
                              priority: Option[Priority],
                            end_stream: Boolean,
                            var buffer: ByteBuffer) extends PartialFrame

  private case class PPromise(streamId: Int,
                            promisedId: Int,
                            var buffer: ByteBuffer) extends PartialFrame

  private var hInfo: PartialFrame = null


  ///////////////////////////////////////////////////////////////////////////

  def onCompleteHeadersFrame(streamId: Int,
                             priority: Option[Priority],
                           end_stream: Boolean,
                              headers: Headers): Http2Result


  def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result

  ////////////////////////////////////////////////////////////////////////////

  final def setMaxHeaderTableSize(maxSize: Int): Unit = { headerDecoder.setMaxTableSize(maxSize) }

  final override def inHeaderSequence(): Boolean = hInfo != null

  final override def onHeadersFrame(streamId: Int,
                                    priority: Option[Priority],
                                 end_headers: Boolean,
                                  end_stream: Boolean,
                                      buffer: ByteBuffer): Http2Result = {

    if (inHeaderSequence()) {
      return Error(PROTOCOL_ERROR("Received HEADERS frame while in in headers sequence", fatal = true))
    }

    if (end_headers) {
      val r = headerDecoder.decode(buffer, streamId)
      if (r.success) {
        val hs = headerDecoder.result()
        onCompleteHeadersFrame(streamId, priority, end_stream, hs)
      }
      else r
    }
    else {
      hInfo = PHeaders(streamId, priority, end_stream, buffer)
      Continue
    }
  }

  final override def onPushPromiseFrame(streamId: Int,
                                      promisedId: Int,
                                     end_headers: Boolean,
                                          buffer: ByteBuffer): Http2Result = {

    if (inHeaderSequence()) {
      val msg = "Received HEADERS frame while in in headers sequence"
      return Error(PROTOCOL_ERROR(msg, fatal = true))
    }

    if (end_headers) {
      val r = headerDecoder.decode(buffer, streamId)
      if (r.success) {
        val hs = headerDecoder.result()
        onCompletePushPromiseFrame(streamId, promisedId, hs)
      }
      else r
    }
    else {
      hInfo = PPromise(streamId, promisedId, buffer)
      Continue
    }
  }

  final override def onContinuationFrame(streamId: Int,
                                      end_headers: Boolean,
                                           buffer: ByteBuffer): Http2Result = {

    if (!inHeaderSequence()) {
      return Error(PROTOCOL_ERROR(s"Invalid CONTINUATION frame: not in partial header frame", streamId, fatal = true))
    }

    if (hInfo.streamId != streamId) {
      val msg = s"Invalid CONTINUATION frame: stream Id's dont match. Expected ${hInfo.streamId}, received $streamId"
      return Error(PROTOCOL_ERROR(msg, streamId, fatal = true))
    }

    val newBuffer = BufferTools.concatBuffers(hInfo.buffer, buffer)
    
    if (end_headers) {
      val r = headerDecoder.decode(newBuffer, streamId)
      if (r.success) {
        val hs = headerDecoder.result()

        val i = hInfo // drop the reference before doing the stateful action
        hInfo = null

        i match {
          case PHeaders(sid, pri, es, _) => onCompleteHeadersFrame(sid, pri, es, hs)
          case PPromise(sid, pro, _)     => onCompletePushPromiseFrame(sid, pro, hs)
        }
      }
      else r
    }
    else {
      hInfo.buffer = newBuffer
      Continue
    }
  }
}
