package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.util.BufferTools

import scala.annotation.tailrec
import scala.collection.mutable.Buffer


private[http20] class NodeMsgEncoder(
    id: Int,
    fencoder: Http20FrameEncoder,
    hencoder: HeaderEncoder) {

  import NodeMsg.{ DataFrame, HeadersFrame }

  /** Encodes messages until they are all done or maxWindow has been reached
    *
    * @param maxPayloadSize Max size to allow a frame payload (not counting the 9 header bytes
    * @param maxWindow Max flow control window bytes allowed to encode
    * @param msgs messages to encode
    * @param acc accumulator to store the resulting ByteBuffers
    * @return the number of flow control bytes written and any unused frames. Note that the
    *         ByteBuffers of data frames may have changed, but the references will be the same.
    */
  def encodeMessages(maxPayloadSize: Int, maxWindow: Int, msgs: Seq[Http2Msg], acc: Buffer[ByteBuffer]): (Int, Seq[Http2Msg]) = {

    @tailrec
    def go(msgs: Seq[Http2Msg], windowDiff: Int): (Int, Seq[Http2Msg]) = {
      if (msgs.nonEmpty) msgs.head match {
        case d: DataFrame if windowDiff < maxWindow =>
          val frameSz = d.data.remaining()
          val frameWindow = encodeDataFrame(maxPayloadSize, maxWindow - windowDiff, d, acc)
          val totalDiff = frameWindow + windowDiff

          if (frameWindow < frameSz) {
            // only wrote a partial frame
            assert(totalDiff == maxWindow)
            (totalDiff, msgs)
          }
          else go(msgs.tail, totalDiff)

        case d: DataFrame => (windowDiff, msgs) // end of window

        case hs: HeadersFrame =>
          encodeHeaders(maxPayloadSize, hs, acc)
          go(msgs.tail, windowDiff)

//        case pp: PushPromiseFrame[HType] =>
//          encodePromiseFrame(maxPayloadSize, pp, acc)
//          go(msgs.tail, windowDiff)

      }
      else (windowDiff, msgs)
    }
    go(msgs, 0)
  }

  /** Encode the HEADERS frame, splitting the payload if required */
  private def encodeHeaders(maxPayloadSize: Int, hs: HeadersFrame, acc: Buffer[ByteBuffer]): Unit = {
    val hsBuff = hencoder.encodeHeaders(hs.headers)
    val priorityBytes = if (hs.priority.nonEmpty) 5 else 0

    if (hsBuff.remaining() + priorityBytes <= maxPayloadSize) {
      acc ++= fencoder.mkHeaderFrame(hsBuff, id, hs.priority, true, hs.endStream, 0)
    } else {
      // need to split into HEADERS and CONTINUATION frames
      val headerBuffer = BufferTools.takeSlice(hsBuff, maxPayloadSize - priorityBytes)
      acc ++= fencoder.mkHeaderFrame(headerBuffer, id, hs.priority, false, hs.endStream, 0)

      // Add the rest of the continuation frames
      mkContinuationFrames(maxPayloadSize, hsBuff, acc)
    }
  }

//  /** Encode the PUSH_PROMISE frame, splitting the payload if required */
//  private def encodePromiseFrame(maxPayloadSize: Int, pp: PushPromiseFrame[HType], acc: Buffer[ByteBuffer]): Unit = {
//    val hsBuff = hencoder.encodeHeaders(pp.headers)
//
//    if (4 + hsBuff.remaining() <= maxPayloadSize) {
//      acc ++= fencoder.mkPushPromiseFrame(id, pp.promisedId, true, 0, hsBuff)
//    }
//    else {
//      // must split it
//      val l = hsBuff.limit()
//      hsBuff.limit(hsBuff.position() + maxPayloadSize - 4)
//      acc ++= fencoder.mkPushPromiseFrame(id, pp.promisedId, false, 0, hsBuff.slice())
//      // Add the rest of the continuation frames
//      hsBuff.limit(l)
//      mkContinuationFrames(maxPayloadSize, hsBuff, acc)
//    }
//  }

  // Split the remaining header data into CONTINUATION frames.
  @tailrec
  private def mkContinuationFrames(maxPayload: Int, hBuff: ByteBuffer, acc: Buffer[ByteBuffer]): Unit = {
    if (hBuff.remaining() <= maxPayload) {
      acc ++= fencoder.mkContinuationFrame(id, true, hBuff)
    }
    else {
      val thisBuffer = BufferTools.takeSlice(hBuff, maxPayload)
      acc ++= fencoder.mkContinuationFrame(id, false, thisBuffer)
      mkContinuationFrames(maxPayload, hBuff, acc)
    }

  }

  /** Encodes as much of the DataFrame as allowed, but it may end unconsumed */
  private def encodeDataFrame(maxPayloadSize: Int, maxWindow: Int, frame: DataFrame, acc: Buffer[ByteBuffer]): Int = {
    val data = frame.data
    val frameSize = data.remaining()
    var windowDiff = 0

    do {
      val maxPayload = math.min(maxPayloadSize, maxWindow - windowDiff)
      val sz = data.remaining()

      if (sz > maxPayload) { // can write a partial frame
        val l = data.limit()
        val end = data.position() + maxPayload
        data.limit(end)
        acc ++= fencoder.mkDataFrame(data.slice(), id, false, 0)
        data.limit(l).position(end)
        windowDiff += maxPayload
      }
      else {
        acc ++= fencoder.mkDataFrame(data, id, frame.endStream, 0)
        windowDiff += sz
      }

    } while (windowDiff < maxWindow && windowDiff < frameSize)

    windowDiff
  }
}

