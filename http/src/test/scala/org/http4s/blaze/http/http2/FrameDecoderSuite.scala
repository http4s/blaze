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

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.bits.Flags
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.mocks.MockFrameListener
import org.http4s.blaze.http.http2.Priority.Dependent
import org.http4s.blaze.testkit.BlazeTestSuite

class FrameDecoderSuite extends BlazeTestSuite {
  def buffer(data: Byte*): ByteBuffer =
    ByteBuffer.wrap(data.toArray)

  private class DataListener extends MockFrameListener(false) {
    var streamId: Option[Int] = None
    var endStream: Option[Boolean] = None
    var data: ByteBuffer = null
    var flowSize: Option[Int] = None
    override def onDataFrame(
        streamId: Int,
        endStream: Boolean,
        data: ByteBuffer,
        flowSize: Int): Result = {
      this.streamId = Some(streamId)
      this.endStream = Some(endStream)
      this.data = data
      this.flowSize = Some(flowSize)
      Continue
    }
  }

  //  +-----------------------------------------------+
  //  |                 Length (24)                   |
  //  +---------------+---------------+---------------+
  //  |   Type (8)    |   Flags (8)   |
  //  +-+-------------+---------------+-------------------------------+
  //  |R|                 Stream Identifier (31)                      |
  //  +=+=============================================================+
  //  |                   Frame Payload (0...)                      ...
  //  +---------------------------------------------------------------+

  test("DATA. Decode basic frame") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(0x00, 0x00, 0x08, // length
      0x00, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    assertEquals(dec.decodeBuffer(testData), Continue)

    listener.streamId = Some(1)
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.data, ByteBuffer.wrap(new Array[Byte](8)))
    assertEquals(listener.flowSize, Some(8))
  }

  test("DATA. Decode basic frame without data") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(
      0x00, 0x00, 0x00, // length
      0x00, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01 // streamId
    ) // no data
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    assertEquals(dec.decodeBuffer(testData), Continue)

    listener.streamId = Some(1)
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.data, ByteBuffer.wrap(new Array[Byte](0)))
    assertEquals(listener.flowSize, Some(0))
  }

  test("DATA. Basic frame with end-stream") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x00, // type
      Flags.END_STREAM, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00)
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    assertEquals(dec.decodeBuffer(testData), Continue)

    assertEquals(listener.endStream, Some(true))
  }

  test("DATA. Basic frame with padding of 0 length") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x00, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x00, // padding length
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00)
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    assertEquals(dec.decodeBuffer(testData), Continue)

    listener.streamId = Some(1)
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.data, ByteBuffer.wrap(new Array[Byte](7)))
    assertEquals(listener.flowSize, Some(8))
  }

  test("DATA. Basic frame with padding of length equal to the remaining body length") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x00, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x07, // padding length
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00)
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    assertEquals(dec.decodeBuffer(testData), Continue)

    listener.streamId = Some(1)
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.data, buffer())
    assertEquals(listener.flowSize, Some(8))
  }

  test("DATA. Basic frame with padding of length equal to the body") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x00, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x08, // padding length
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00)
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("DATA. Not allow DATA on stream 0") {
    // DATA frame:
    val testData = buffer(0x00, 0x00, 0x08, // length
      0x00, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val listener = new DataListener
    val dec = new FrameDecoder(Http2Settings.default, listener)
    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class HeadersListener extends MockFrameListener(false) {
    // For handling unknown stream frames
    var streamId: Option[Int] = None
    var priority: Option[Priority] = None
    var endHeaders: Option[Boolean] = None
    var endStream: Option[Boolean] = None
    var buffer: ByteBuffer = null

    override def onHeadersFrame(
        streamId: Int,
        priority: Priority,
        end_headers: Boolean,
        end_stream: Boolean,
        buffer: ByteBuffer): Result = {
      this.streamId = Some(streamId)
      this.priority = Some(priority)
      this.endHeaders = Some(end_headers)
      this.endStream = Some(end_stream)
      this.buffer = buffer
      Continue
    }
  }

  //  +---------------+
  //  |Pad Length? (8)|
  //  +-+-------------+-----------------------------------------------+
  //  |E|                 Stream Dependency? (31)                     |
  //  +-+-------------+-----------------------------------------------+
  //  |  Weight? (8)  |
  //  +-+-------------+-----------------------------------------------+
  //  |                   Header Block Fragment (*)                 ...
  //  +---------------------------------------------------------------+
  //  |                           Padding (*)                       ...
  //  +---------------------------------------------------------------+

  test("HEADERS. Basic HEADERS") {
    // HEADERS frame:
    val testData = buffer(0x00, 0x00, 0x08, // length
      0x01, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.NoPriority: Priority))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.buffer, ByteBuffer.wrap(new Array(8)))
  }

  test("HEADERS. Basic HEADERS with exclusive priority") {
    // HEADERS frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x01, // type
      Flags.PRIORITY, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      (1 << 7).toByte,
      0x00,
      0x00,
      0x02, // stream dependency, exclusive
      0xff.toByte, // weight
      0x00,
      0x00,
      0x00
    )
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.Dependent(2, true, 256)))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.buffer, ByteBuffer.wrap(new Array(3)))
  }

  test("HEADERS. Basic HEADERS with pad length 0") {
    // HEADERS frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x01, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00)
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.NoPriority: Priority))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.buffer, ByteBuffer.wrap(new Array(7)))
  }

  test("HEADERS. Basic HEADERS with padding") {
    // HEADERS frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x01, // type
      (Flags.PADDED | Flags.PRIORITY).toByte, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x01, // padding of 1
      0x00,
      0x00,
      0x00,
      0x02, // dependent stream 2, non-exclusive
      0x02, // weight 3
      0x00,
      0x01
    )
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.Dependent(2, false, 3)))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.buffer, buffer(0x00))
  }

  test("HEADERS. Basic HEADERS with padding of remaining size") {
    // HEADERS frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x01, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x07, // padding of 7
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x01)
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.NoPriority: Priority))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.endStream, Some(false))
    assertEquals(listener.buffer, buffer())
  }

  test("HEADERS. Not allow HEADERS on stream 0") {
    // HEADERS frame:
    val testData = buffer(0x00, 0x00, 0x08, // length
      0x01, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("HEADERS. HEADERS with priority on itself") {
    // HEADERS frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x01, // type
      Flags.PRIORITY, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x00,
      0x00,
      0x00,
      0x01, // dependent stream 1, non-exclusive
      0x02, // weight 3
      0x00,
      0x00,
      0x01
    )
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("HEADERS. HEADERS with dependency on stream 0") {
    // HEADERS frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x01, // type
      Flags.PRIORITY, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      0x00,
      0x00,
      0x00,
      0x00, // dependent stream 0, non-exclusive
      0x02, // weight 3
      0x00,
      0x00,
      0x01
    )
    val listener = new HeadersListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
  }

  private class PriorityListener extends MockFrameListener(false) {
    var streamId: Option[Int] = None
    var priority: Option[Dependent] = None
    override def onPriorityFrame(streamId: Int, priority: Dependent): Result = {
      this.streamId = Some(streamId)
      this.priority = Some(priority)
      Continue
    }
  }

  //  +-+-------------------------------------------------------------+
  //  |E|                  Stream Dependency (31)                     |
  //  +-+-------------+-----------------------------------------------+
  //  |   Weight (8)  |
  //  +-+-------------+

  test("PRIORITY. Simple PRIORITY frame") {
    // PRIORITY frame:
    val testData = buffer(0x00, 0x00, 0x05, // length
      0x02, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x02, 0x00)
    val listener = new PriorityListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.Dependent(2, false, 1)))
  }

  test("PRIORITY. Simple PRIORITY frame with exclusive") {
    // PRIORITY frame:
    val testData = buffer(
      0x00,
      0x00,
      0x05, // length
      0x02, // type
      0x00, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      (1 << 7).toByte,
      0x00,
      0x00,
      0x02, // stream dependency 2
      0x00)
    val listener = new PriorityListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.priority, Some(Priority.Dependent(2, true, 1)))
  }

  test("PRIORITY. Frame with dependent stream being itself") {
    // PRIORITY frame:
    val testData = buffer(0x00, 0x00, 0x05, // length
      0x02, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x01, // stream dependency
      0x00) // weight
    val listener = new PriorityListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2StreamException(1, Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("PRIORITY. Frame with too large of body") {
    // PRIORITY frame:
    val testData = buffer(0x00, 0x00, 0x06, // length
      0x02, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x02, // stream dependency
      0x00, // weight
      0x11) // extra byte
    val listener = new PriorityListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2StreamException(1, Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("PRIORITY. Frame with too small of body") {
    // PRIORITY frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x02, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x02 // stream dependency
    ) // missing weight
    val listener = new PriorityListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2StreamException(1, Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class RstListener extends MockFrameListener(false) {
    var streamId: Option[Int] = None
    var code: Option[Long] = None
    override def onRstStreamFrame(streamId: Int, code: Long): Result = {
      this.streamId = Some(streamId)
      this.code = Some(code)
      Continue
    }
  }

  //  +---------------------------------------------------------------+
  //  |                        Error Code (32)                        |
  //  +---------------------------------------------------------------+

  test("RST_STREAM. Simple RST. Simple frame") {
    // RST frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x03, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x02 // code
    ) // missing weight
    val listener = new RstListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.code, Some(2L))
  }

  test("RST_STREAM. Simple RST. Stream 0") {
    // RST frame:
    val testData = buffer(
      0x00, 0x00, 0x4, // length
      0x03, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      0x00, 0x00, 0x00, 0x00 // code
    ) // missing weight
    val listener = new RstListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("RST_STREAM. Simple RST. Frame to small") {
    // RST frame:
    val testData = buffer(
      0x00, 0x00, 0x3, // length
      0x03, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00 // code
    ) // missing weight
    val listener = new RstListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("RST_STREAM. Simple RST. Frame to large") {
    // RST frame:
    val testData = buffer(
      0x00, 0x00, 0x5, // length
      0x03, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      0x00, 0x00, 0x00, 0x00, 0x00 // code
    ) // missing weight
    val listener = new RstListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class SettingsListener extends MockFrameListener(false) {
    var settings: Option[Option[Seq[Setting]]] = None
    override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = {
      this.settings = Some(settings)
      Continue
    }
  }

  //  +-------------------------------+
  //  |       Identifier (16)         |
  //  +-------------------------------+-------------------------------+
  //  |                        Value (32)                             |
  //  +---------------------------------------------------------------+

  test("SETTINGS. Simple frame") {
    // SETTING frame:
    val testData = buffer(
      0x00, 0x00, 0x00, // length
      0x04, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00 // streamId
      // body
    )
    val listener = new SettingsListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.settings, Some(Some(Seq.empty[Setting])))
  }

  test("SETTINGS. Simple frame ack") {
    // SETTING frame:
    val testData = buffer(
      0x00,
      0x00,
      0x00, // length
      0x04, // type
      Flags.ACK, // flags
      0x00,
      0x00,
      0x00,
      0x00 // streamId
      // body
    )
    val listener = new SettingsListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.settings, Some(None))
  }

  test("SETTINGS. Simple frame with settings") {
    // SETTING frame:
    val testData = buffer(
      0x00, 0x00, 0x06, // length
      0x04, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      0x00, 0x00, // key
      0x00, 0x00, 0x00, 0x01 // value
      // body
    )
    val listener = new SettingsListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.settings, Some(Some(Seq(Setting(0, 1)))))
  }

  test("SETTINGS. Streamid != 0") {
    // SETTING frame:
    val testData = buffer(
      0x00, 0x00, 0x00, // length
      0x04, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01 // streamId
      // body
    ) // missing weight
    val listener = new SettingsListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("SETTINGS. Settings frame with settings and ack") {
    // SETTING frame:
    val testData = buffer(
      0x00,
      0x00,
      0x06, // length
      0x04, // type
      Flags.ACK, // flags
      0x00,
      0x00,
      0x00,
      0x00, // streamId
      0x00,
      0x00, // key
      0x00,
      0x00,
      0x00,
      0x01 // value
      // body
    )
    val listener = new SettingsListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("SETTINGS. Invalid size") {
    // SETTING frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x04, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x00
    ) // missing weight
    val listener = new SettingsListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class PushListener extends MockFrameListener(false) {
    var streamId: Option[Int] = None
    var promisedId: Option[Int] = None
    var endHeaders: Option[Boolean] = None
    var data: ByteBuffer = null
    override def onPushPromiseFrame(
        streamId: Int,
        promisedId: Int,
        end_headers: Boolean,
        data: ByteBuffer): Result = {
      this.streamId = Some(streamId)
      this.promisedId = Some(promisedId)
      this.endHeaders = Some(end_headers)
      this.data = data
      Continue
    }
  }

  //  +---------------+
  //  |Pad Length? (8)|
  //  +-+-------------+-----------------------------------------------+
  //  |R|                  Promised Stream ID (31)                    |
  //  +-+-----------------------------+-------------------------------+
  //  |                   Header Block Fragment (*)                 ...
  //  +---------------------------------------------------------------+
  //  |                           Padding (*)                       ...
  //  +---------------------------------------------------------------+

  test("PUSH_PROMISE. Simple frame") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x05, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x02 // promised id
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.promisedId, Some(2))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.data, buffer())
  }

  test("PUSH_PROMISE. Simple frame with end headers") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00,
      0x00,
      0x04, // length
      0x05, // type
      Flags.END_HEADERS, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      // body
      0x00,
      0x00,
      0x00,
      0x02 // promised id
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.promisedId, Some(2))
    assertEquals(listener.endHeaders, Some(true))
    assertEquals(listener.data, buffer())
  }

  test("PUSH_PROMISE. Frame with padding of 0") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00,
      0x00,
      0x05, // length
      0x05, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      // body
      0x00, // pad length
      0x00,
      0x00,
      0x00,
      0x02 // promised id
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.promisedId, Some(2))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.data, buffer())
  }

  test("PUSH_PROMISE. Frame with padding of 1 and body") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00,
      0x00,
      0x07, // length
      0x05, // type
      Flags.PADDED, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      // body
      0x01, // pad length
      0x00,
      0x00,
      0x00,
      0x02, // promised id
      0x00,
      0x01 // the padding
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.promisedId, Some(2))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.data, buffer(0x00))
  }

  test("PUSH_PROMISE. Illegal streamid") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x05, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x02 // promised id
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("PUSH_PROMISE. Illegal push stream id") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x05, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x01 // promised id
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("PUSH_PROMISE. Streamid == push streamid") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x05, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x01 // promised id
    )
    val listener = new PushListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class PingListener extends MockFrameListener(false) {
    var ack: Option[Boolean] = None
    var data: Option[Array[Byte]] = None

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = {
      this.ack = Some(ack)
      this.data = Some(data)
      Continue
    }
  }

  //  +---------------------------------------------------------------+
  //  |                                                               |
  //  |                      Opaque Data (64)                         |
  //  |                                                               |
  //  +---------------------------------------------------------------+

  test("PING. Simple frame") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x08, // length
      0x06, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x00, // opaque data
      0x00, 0x00, 0x00, 0x00
    )
    val listener = new PingListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.ack, Some(false))
    assertEquals(listener.data.map(_.sameElements(new Array[Byte](8))), Some(true))
  }

  test("PING. Simple frame ack") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00,
      0x00,
      0x08, // length
      0x06, // type
      Flags.ACK, // flags
      0x00,
      0x00,
      0x00,
      0x00, // streamId
      // body
      0x00,
      0x00,
      0x00,
      0x00, // opaque data
      0x00,
      0x00,
      0x00,
      0x00
    )
    val listener = new PingListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.ack, Some(true))
    assertEquals(listener.data.map(_.sameElements(new Array[Byte](8))), Some(true))
  }

  test("PING. Stream id != 0") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x08, // length
      0x06, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x00, // opaque data
      0x00, 0x00, 0x00, 0x00
    )
    val listener = new PingListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("PING. Too small") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x07, // length
      0x06, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x00, // opaque data
      0x00, 0x00, 0x00
    )
    val listener = new PingListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("PING. Too large") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x09, // length
      0x06, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x00, // opaque data
      0x00, 0x00, 0x00, 0x00, 0x00
    )
    val listener = new PingListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class GoAwayListener extends MockFrameListener(false) {
    var lastStream: Option[Int] = None
    var errorCode: Option[Long] = None
    var debugData: Option[Array[Byte]] = None
    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = {
      this.lastStream = Some(lastStream)
      this.errorCode = Some(errorCode)
      this.debugData = Some(debugData)
      Continue
    }
  }

  //  +-+-------------------------------------------------------------+
  //  |R|                  Last-Stream-ID (31)                        |
  //  +-+-------------------------------------------------------------+
  //  |                      Error Code (32)                          |
  //  +---------------------------------------------------------------+
  //  |                  Additional Debug Data (*)                    |
  //  +---------------------------------------------------------------+

  test("GOAWAY. Simple frame") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x08, // length
      0x07, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x01, // last stream
      0x00, 0x00, 0x00, 0x00 // error code
    )
    val listener = new GoAwayListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.lastStream, Some(1))
    assertEquals(listener.errorCode, Some(0L))
    assertEquals(listener.debugData.map(_.sameElements(new Array[Byte](0))), Some(true))
  }

  test("GOAWAY. Simple frame with data") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x09, // length
      0x07, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x01, // last stream
      0x00, 0x00, 0x00, 0x00, // error code
      0x01
    )
    val listener = new GoAwayListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.lastStream, Some(1))
    assertEquals(listener.errorCode, Some(0L))
    assertEquals(listener.debugData.map(_.sameElements(Array[Byte](0x01))), Some(true))
  }

  test("GOAWAY. Streamid != 0") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x09, // length
      0x07, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x01, // last stream
      0x00, 0x00, 0x00, 0x00, // error code
      0x01
    )
    val listener = new GoAwayListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class WindowListener extends MockFrameListener(false) {
    var streamId: Option[Int] = None
    var sizeIncrement: Option[Int] = None
    override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result = {
      this.streamId = Some(streamId)
      this.sizeIncrement = Some(sizeIncrement)
      Continue
    }
  }

  //  +-+-------------------------------------------------------------+
  //  |R|              Window Size Increment (31)                     |
  //  +-+-------------------------------------------------------------+

  test("WINDOW_UPDATE. Simple frame") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x08, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x01 // increment
    )
    val listener = new WindowListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(0))
    assertEquals(listener.sizeIncrement, Some(1))
  }

  test("WINDOW_UPDATE. Increment 0 on session") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x08, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x00 // increment
    )
    val listener = new WindowListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("WINDOW_UPDATE. Increment 0 on stream") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x04, // length
      0x08, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x00, 0x00, 0x00, 0x00 // increment
    )
    val listener = new WindowListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2StreamException(1, Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("WINDOW_UPDATE. Too small") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x03, // length
      0x08, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00 // increment
    )
    val listener = new WindowListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("WINDOW_UPDATE. Too large") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x05, // length
      0x08, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x00, 0x00, 0x00, 0x01, // increment
      0x00
    )
    val listener = new WindowListener
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  private class ContinuationListener(inseq: Boolean) extends MockFrameListener(inseq) {
    var streamId: Option[Int] = None
    var endHeaders: Option[Boolean] = None
    var data: ByteBuffer = null
    override def onContinuationFrame(
        streamId: Int,
        endHeaders: Boolean,
        data: ByteBuffer): Result = {
      this.streamId = Some(streamId)
      this.endHeaders = Some(endHeaders)
      this.data = data
      Continue
    }
  }

  //  +---------------------------------------------------------------+
  //  |                   Header Block Fragment (*)                 ...
  //  +---------------------------------------------------------------+

  test("CONTINUATION. Simple frame") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x01, // length
      0x09, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x01, // streamId
      // body
      0x01 // increment
    )
    val listener = new ContinuationListener(true)
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.endHeaders, Some(false))
    assertEquals(listener.data, buffer(0x01))
  }

  test("CONTINUATION. StreamId == 0") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00, 0x00, 0x01, // length
      0x09, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      // body
      0x01 // increment
    )
    val listener = new ContinuationListener(true)
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

  test("CONTINUATION. end_headers") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00,
      0x00,
      0x01, // length
      0x09, // type
      Flags.END_HEADERS, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      // body
      0x01 // increment
    )
    val listener = new ContinuationListener(true)
    val dec = new FrameDecoder(Http2Settings.default, listener)

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(listener.streamId, Some(1))
    assertEquals(listener.endHeaders, Some(true))
    assertEquals(listener.data, buffer(0x01))
  }

  test("CONTINUATION. Not in headers sequence") {
    // PUSH_PROMISE frame:
    val testData = buffer(
      0x00,
      0x00,
      0x01, // length
      0x09, // type
      Flags.END_HEADERS, // flags
      0x00,
      0x00,
      0x00,
      0x01, // streamId
      // body
      0x01 // increment
    )
    val listener = new ContinuationListener(false)
    val dec = new FrameDecoder(Http2Settings.default, listener)

    dec.decodeBuffer(testData) match {
      case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ()
      case _ => fail("Unexpected decodeBuffer result found")
    }
  }

//  case FrameTypes.CONTINUATION  => decodeContinuationFrame(buffer, streamId, flags)

  test("Unknown frame types pass the data to the extension frame method") {
    // UNKONWN Frame:
    // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
    val testData = buffer(0x00, 0x00, 0x08, // length
      0x16, // type
      0x00, // flags
      0x00, 0x00, 0x00, 0x00, // streamId
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    val listener = new MockFrameListener(false)
    var data: ByteBuffer = null
    var code: Option[Byte] = None
    var flags: Option[Byte] = None
    var streamId: Option[Int] = None
    val dec = new FrameDecoder(Http2Settings.default, listener) {
      override def onExtensionFrame(
          _code: Byte,
          _streamId: Int,
          _flags: Byte,
          buffer: ByteBuffer): Result = {
        data = buffer
        code = Some(_code)
        streamId = Some(_streamId)
        flags = Some(_flags)
        Continue
      }
    }

    assertEquals(dec.decodeBuffer(testData), Continue)
    assertEquals(data, ByteBuffer.wrap(new Array[Byte](8)))
    assertEquals(code, Some(0x16: Byte))
    assertEquals(flags, Some(0x00: Byte))
    assertEquals(streamId, Some(0))
  }
}
