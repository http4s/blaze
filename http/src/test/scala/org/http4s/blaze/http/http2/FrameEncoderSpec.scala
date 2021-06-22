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

package org.http4s.blaze
package http
package http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.mocks.MockTools
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

class FrameEncoderSpec extends Specification {
  import CodecUtils._

  private def mockListener(
      onDataFrameMock: (Int, Boolean, ByteBuffer, Int) => Result = (_, _, _, _) => BufferUnderflow,
      onHeadersFrameMock: (Int, Priority, Boolean, Boolean, ByteBuffer) => Result =
        (_, _, _, _, _) => BufferUnderflow,
      inHeaderSequenceMock: Boolean = false,
      onContinuationFrameMock: (Int, Boolean, ByteBuffer) => Result = (_, _, _) => BufferUnderflow
  ) = new FrameListener {
    def inHeaderSequence: Boolean = inHeaderSequenceMock

    def onDataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer, flowSize: Int): Result =
      onDataFrameMock(streamId, endStream, data, flowSize)

    def onHeadersFrame(
        streamId: Int,
        priority: Priority,
        endHeaders: Boolean,
        endStream: Boolean,
        data: ByteBuffer): Result =
      onHeadersFrameMock(streamId, priority, endHeaders, endStream, data)

    def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Result =
      onContinuationFrameMock(streamId, endHeaders, data)

    def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result = ???

    def onRstStreamFrame(streamId: Int, code: Long): Result = ???

    def onSettingsFrame(settings: Option[Seq[Http2Settings.Setting]]): Result = ???

    def onPushPromiseFrame(
        streamId: Int,
        promisedId: Int,
        end_headers: Boolean,
        data: ByteBuffer): Result = ???

    def onPingFrame(ack: Boolean, data: Array[Byte]): Result = ???

    def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = ???

    def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result = ???
  }

  // increase the ID by 25 to lift it out of the normal h2 exception codes
  private def ReturnTag(id: Int): Error =
    Error(Http2Exception.errorGenerator(id.toLong + 25L).goaway())

  "Http2FrameEncoder" >> {
    "not fragment data frames if they fit into a single frame" >> {
      val tools = new MockTools(true)
      val zeroBuffer15 = zeroBuffer(15)

      val listener = mockListener {
        case (1, true, `zeroBuffer15`, 15) => ReturnTag(1)
        case (1, false, `zeroBuffer15`, 15) => ReturnTag(2)
        case _ => throw new IllegalStateException("Unexpected arguments for onDataFrame")
      }
      val decoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 15 // technically an illegal size...

      // Frame 1 `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, true, zeroBuffer(15)))
      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // Frame 2 `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, false, zeroBuffer(15)))
      decoder.decodeBuffer(data2) must_== ReturnTag(2)
    }

    "fragments data frames if they exceed the localSettings.maxFrameSize" >> {
      val tools = new MockTools(true)
      val zeroBuffer5 = zeroBuffer(5)
      val zeroBuffer10 = zeroBuffer(10)
      val listener = mockListener(onDataFrameMock = {
        case (1, false, `zeroBuffer10`, 10) => ReturnTag(1)
        case (1, true, `zeroBuffer5`, 5) => ReturnTag(2)
        case _ => throw new IllegalStateException("Unexpected arguments for onDataFrame")
      })
      val remoteDecoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 10 // technically an illegal size...

      // `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, true, zeroBuffer(15)))

      // Frame 1
      remoteDecoder.decodeBuffer(data1) must_== ReturnTag(1)

      // Frame 2
      remoteDecoder.decodeBuffer(data1) must_== ReturnTag(2)

      // `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, false, zeroBuffer(15)))

      val listener2 = mockListener(onDataFrameMock = {
        case (1, false, `zeroBuffer10`, 10) => ReturnTag(3)
        case (1, false, `zeroBuffer5`, 5) => ReturnTag(4)
        case _ => throw new IllegalStateException("Unexpected arguments for onDataFrame")
      })
      val remoteDecoder2 = new FrameDecoder(tools.remoteSettings, listener2)

      // Frame 1
      remoteDecoder2.decodeBuffer(data2) must_== ReturnTag(3)

      // Frame 2
      remoteDecoder2.decodeBuffer(data2) must_== ReturnTag(4)
    }

    "not fragment headers if they fit into a single frame" >> {
      val tools = new MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Headers): ByteBuffer = zeroBuffer(15)
        }
      }
      val zeroBuffer15 = zeroBuffer(15)

      val listener = mockListener(onHeadersFrameMock = {
        case (1, Priority.NoPriority, true, true, `zeroBuffer15`) => ReturnTag(1)
        case (1, Priority.NoPriority, true, false, `zeroBuffer15`) => ReturnTag(2)
        case _ => throw new IllegalStateException("Unexpected arguments for onHeadersFrame")
      })
      val decoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 15 // technically an illegal size...
      // `endStream = true`
      val data1 =
        BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, true, Nil))

      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // `endStream = false`
      val data2 =
        BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, false, Nil))

      decoder.decodeBuffer(data2) must_== ReturnTag(2)
    }

    "fragment headers if they don't fit into one frame" >> {
      val tools = new MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Headers): ByteBuffer = zeroBuffer(15)
        }
      }

      val zeroBuffer10 = zeroBuffer(10)

      val listener1 = mockListener(
        onHeadersFrameMock = {
          case (1, Priority.NoPriority, false, true, `zeroBuffer10`) => ReturnTag(1)
          case _ => throw new IllegalStateException("Unexpected arguments for onHeadersFrame")
        }
      )
      val decoder1 = new FrameDecoder(tools.remoteSettings, listener1)

      tools.remoteSettings.maxFrameSize = 10 // technically an illegal size...
      val data =
        BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, true, Nil))

      decoder1.decodeBuffer(data) must_== ReturnTag(1)

      val zeroBuffer5 = zeroBuffer(5)
      val listener2 = mockListener(
        onHeadersFrameMock = { case _ =>
          null
        },
        onContinuationFrameMock = {
          case (1, true, `zeroBuffer5`) => ReturnTag(2)
          case _ => throw new IllegalStateException("Unexpected arguments for onContinuationFrame")
        },
        inHeaderSequenceMock = true
      )
      val decoder2 = new FrameDecoder(tools.remoteSettings, listener2)

      decoder2.decodeBuffer(data) must_== ReturnTag(2)
    }

    "fragmenting HEADERS frames considers priority info size" >> {
      val tools = new MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Headers): ByteBuffer = zeroBuffer(10)
        }
      }

      val zeroBuffer5 = zeroBuffer(5)
      val p = Priority.Dependent(2, true, 12)

      val listener1 = mockListener(
        onHeadersFrameMock = {
          case (1, `p`, false, true, `zeroBuffer5`) => ReturnTag(1)
          case _ => throw new IllegalStateException("Unexpected arguments for onHeadersFrame")
        }
      )
      val decoder1 = new FrameDecoder(tools.remoteSettings, listener1)

      tools.remoteSettings.maxFrameSize = 10 // technically an illegal size...

      val data = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, p, true, Nil))

      decoder1.decodeBuffer(data) must_== ReturnTag(1)

      val listener2 = mockListener(
        onHeadersFrameMock = { case _ =>
          null
        },
        onContinuationFrameMock = {
          case (1, true, `zeroBuffer5`) => ReturnTag(2)
          case _ => throw new IllegalStateException("Unexpected arguments for onHeadersFrame")
        },
        inHeaderSequenceMock = true
      )
      val decoder2 = new FrameDecoder(tools.remoteSettings, listener2)

      decoder2.decodeBuffer(data) must_== ReturnTag(2)
    }
  }
}
