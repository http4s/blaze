package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.Priority.Dependent
import org.http4s.blaze.http.http2.bits.Flags
import org.http4s.blaze.http.http2.mocks.MockFrameListener
import org.specs2.mutable.Specification

class FrameDecoderSpec extends Specification {

  def buffer(data: Byte*): ByteBuffer =
    ByteBuffer.wrap(data.toArray)

  //  +-----------------------------------------------+
  //  |                 Length (24)                   |
  //  +---------------+---------------+---------------+
  //  |   Type (8)    |   Flags (8)   |
  //  +-+-------------+---------------+-------------------------------+
  //  |R|                 Stream Identifier (31)                      |
  //  +=+=============================================================+
  //  |                   Frame Payload (0...)                      ...
  //  +---------------------------------------------------------------+

  "DATA" >> {
    class DataListener extends MockFrameListener(false) {
      var streamId: Option[Int] = None
      var endStream: Option[Boolean] = None
      var data: ByteBuffer = null
      var flowSize: Option[Int] = None
      override def onDataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer, flowSize: Int): Result = {
        this.streamId = Some(streamId)
        this.endStream = Some(endStream)
        this.data = data
        this.flowSize = Some(flowSize)
        Continue
      }
    }

    "Decode basic frame" >> {
      // UNKONWN Frame:
      // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x00, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new DataListener
      val dec = new FrameDecoder(Http2Settings.default, listener)
      dec.decodeBuffer(testData) must_== Continue

      listener.streamId = Some(1)
      listener.endStream must_== Some(false)
      listener.data must_== ByteBuffer.wrap(new Array[Byte](8))
      listener.flowSize must_== Some(8)
    }

    "Decode basic frame without data" >> {
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
      dec.decodeBuffer(testData) must_== Continue

      listener.streamId = Some(1)
      listener.endStream must_== Some(false)
      listener.data must_== ByteBuffer.wrap(new Array[Byte](0))
      listener.flowSize must_== Some(0)
    }

    "basic frame with end-stream" >> {
      // UNKONWN Frame:
      // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x00, // type
        Flags.END_STREAM, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new DataListener
      val dec = new FrameDecoder(Http2Settings.default, listener)
      dec.decodeBuffer(testData) must_== Continue

      listener.endStream must_== Some(true)
    }

    "basic frame with padding of 0 length" >> {
      // UNKONWN Frame:
      // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x00, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, // padding length
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new DataListener
      val dec = new FrameDecoder(Http2Settings.default, listener)
      dec.decodeBuffer(testData) must_== Continue

      listener.streamId = Some(1)
      listener.endStream must_== Some(false)
      listener.data must_== ByteBuffer.wrap(new Array[Byte](7))
      listener.flowSize must_== Some(8)
    }

    "basic frame with padding of length equal to the remaining body length" >> {
      // UNKONWN Frame:
      // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x00, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x07, // padding length
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new DataListener
      val dec = new FrameDecoder(Http2Settings.default, listener)
      dec.decodeBuffer(testData) must_== Continue

      listener.streamId = Some(1)
      listener.endStream must_== Some(false)
      listener.data must_== buffer()
      listener.flowSize must_== Some(8)
    }

    "basic frame with padding of length equal to the body" >> {
      // UNKONWN Frame:
      // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x00, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x08, // padding length
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new DataListener
      val dec = new FrameDecoder(Http2Settings.default, listener)
      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "not allow DATA on stream 0" >> {
      // DATA frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x00, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new DataListener
      val dec = new FrameDecoder(Http2Settings.default, listener)
      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
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

  "HEADERS" >> {
    class HeadersListener extends MockFrameListener(false) {
      // For handling unknown stream frames
      var streamId: Option[Int] = None
      var priority: Option[Priority] = None
      var endHeaders: Option[Boolean] = None
      var endStream: Option[Boolean] = None
      var buffer: ByteBuffer = null

      override def onHeadersFrame(streamId: Int,
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

    "basic HEADERS" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.NoPriority)
      listener.endHeaders must beSome(false)
      listener.endStream must beSome(false)
      listener.buffer must_== ByteBuffer.wrap(new Array(8))
    }

    "basic HEADERS with exclusive priority" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        Flags.PRIORITY, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        (1 << 7).toByte, 0x00, 0x00, 0x02, // stream dependency, exclusive
        0xff.toByte, // weight
        0x00, 0x00, 0x00)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.Dependent(2, true, 256))
      listener.endHeaders must beSome(false)
      listener.endStream must beSome(false)
      listener.buffer must_== ByteBuffer.wrap(new Array(3))
    }

    "basic HEADERS with pad length 0" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.NoPriority)
      listener.endHeaders must beSome(false)
      listener.endStream must beSome(false)
      listener.buffer must_== ByteBuffer.wrap(new Array(7))
    }

    "basic HEADERS with padding" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        (Flags.PADDED | Flags.PRIORITY).toByte, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x01, // padding of 1
        0x00, 0x00, 0x00, 0x02, // dependent stream 2, non-exclusive
        0x02, // weight 3
        0x00, 0x01)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.Dependent(2, false, 3))
      listener.endHeaders must beSome(false)
      listener.endStream must beSome(false)
      listener.buffer must_== buffer(0x00)
    }

    "basic HEADERS with padding of remaining size" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x07, // padding of 7
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.NoPriority)
      listener.endHeaders must beSome(false)
      listener.endStream must beSome(false)
      listener.buffer must_== buffer()
    }

    "not allow HEADERS on stream 0" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "HEADERS with priority on itself" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        Flags.PRIORITY, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x01, // dependent stream 1, non-exclusive
        0x02, // weight 3
        0x00, 0x00, 0x01)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "HEADERS with dependency on stream 0" >> {
      // HEADERS frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x01, // type
        Flags.PRIORITY, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x00, // dependent stream 0, non-exclusive
        0x02, // weight 3
        0x00, 0x00, 0x01)
      val listener = new HeadersListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
    }
  }

  "PRIORITY" >> {

    class PriorityListener extends MockFrameListener(false) {
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

    "simple PRIORITY frame" >> {
      // PRIORITY frame:
      val testData = buffer(
        0x00, 0x00, 0x05, // length
        0x02, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x02,
        0x00)
      val listener = new PriorityListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.Dependent(2, false, 1))
    }

    "simple PRIORITY frame with exclusive" >> {
      // PRIORITY frame:
      val testData = buffer(
        0x00, 0x00, 0x05, // length
        0x02, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        (1 << 7).toByte, 0x00, 0x00, 0x02, // stream dependency 2
        0x00)
      val listener = new PriorityListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.priority must beSome(Priority.Dependent(2, true, 1))
    }

    "frame with dependent stream being itself" >> {
      // PRIORITY frame:
      val testData = buffer(
        0x00, 0x00, 0x05, // length
        0x02, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x01, // stream dependency
        0x00) // weight
      val listener = new PriorityListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2StreamException(1, Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "frame with too large of body" >> {
      // PRIORITY frame:
      val testData = buffer(
        0x00, 0x00, 0x06, // length
        0x02, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        0x00, 0x00, 0x00, 0x02, // stream dependency
        0x00,  // weight
        0x11) // extra byte
      val listener = new PriorityListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2StreamException(1, Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }

    "frame with too small of body" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2StreamException(1, Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }
  }

  "RST_STREAM" >> {

    class RstListener extends MockFrameListener(false) {
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

    "Simple RST" >> {
      "simple frame" >> {
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

        dec.decodeBuffer(testData) must_== Continue
        listener.streamId must beSome(1)
        listener.code must beSome(2)
      }

      "stream 0" >> {
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

        dec.decodeBuffer(testData) must beLike {
          case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
        }
      }

      "frame to small" >> {
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

        dec.decodeBuffer(testData) must beLike {
          case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
        }
      }

      "frame to large" >> {
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

        dec.decodeBuffer(testData) must beLike {
          case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
        }
      }
    }
  }

  "SETTINGS" >> {
    class SettingsListener extends MockFrameListener(false) {
      var ack: Option[Boolean] = None
      var settings: Option[Seq[Setting]] = None
      override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Result = {
        this.ack = Some(ack)
        this.settings = Some(settings)
        Continue
      }
    }

    //  +-------------------------------+
    //  |       Identifier (16)         |
    //  +-------------------------------+-------------------------------+
    //  |                        Value (32)                             |
    //  +---------------------------------------------------------------+

    "simple frame" >> {
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

      dec.decodeBuffer(testData) must_== Continue
      listener.ack must beSome(false)
      listener.settings must_== Some(Seq.empty[Setting])
    }

    "simple frame ack" >> {
      // SETTING frame:
      val testData = buffer(
        0x00, 0x00, 0x00, // length
        0x04, // type
        Flags.ACK, // flags
        0x00, 0x00, 0x00, 0x00 // streamId
        // body
      )
      val listener = new SettingsListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.ack must beSome(true)
      listener.settings must_== Some(Seq.empty[Setting])
    }

    "simple frame with settings" >> {
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

      dec.decodeBuffer(testData) must_== Continue
      listener.ack must beSome(false)
      listener.settings must_== Some(Seq(Setting(0, 1)))
    }

    "streamid != 0" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "settings frame with settings and ack" >> {
      // SETTING frame:
      val testData = buffer(
        0x00, 0x00, 0x06, // length
        0x04, // type
        Flags.ACK, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        0x00, 0x00, // key
        0x00, 0x00, 0x00, 0x01 // value
        // body
      )
      val listener = new SettingsListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }

    "invalid size" >> {
      // SETTING frame:
      val testData = buffer(
        0x00, 0x00, 0x04, // length
        0x04, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x00, 0x00,0x00,0x00
      ) // missing weight
      val listener = new SettingsListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }
  }

  "PUSH_PROMISE" >> {
    class PushListener extends MockFrameListener(false) {
      var streamId: Option[Int] = None
      var promisedId: Option[Int] = None
      var endHeaders: Option[Boolean] = None
      var data: ByteBuffer = null
      override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): Result = {
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

    "simple frame" >> {
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

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.promisedId must beSome(2)
      listener.endHeaders must beSome(false)
      listener.data must_== buffer()
    }

    "simple frame with end headers" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x04, // length
        0x05, // type
        Flags.END_HEADERS, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x00, 0x00, 0x00, 0x02 // promised id
      )
      val listener = new PushListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.promisedId must beSome(2)
      listener.endHeaders must beSome(true)
      listener.data must_== buffer()
    }

    "frame with padding of 0" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x05, // length
        0x05, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x00, // pad length
        0x00, 0x00, 0x00, 0x02 // promised id
      )
      val listener = new PushListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.promisedId must beSome(2)
      listener.endHeaders must beSome(false)
      listener.data must_== buffer()
    }

    "frame with padding of 1 and body" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x07, // length
        0x05, // type
        Flags.PADDED, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x01, // pad length
        0x00, 0x00, 0x00, 0x02, // promised id
        0x00,
        0x01 // the padding
      )
      val listener = new PushListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.promisedId must beSome(2)
      listener.endHeaders must beSome(false)
      listener.data must_== buffer(0x00)
    }

    "illegal streamid" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "illegal push stream id" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "streamid == push streamid" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }
  }

  "PING" >> {
    class PingListener extends MockFrameListener(false) {
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

    "simple frame" >> {
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

      dec.decodeBuffer(testData) must_== Continue
      listener.ack must beSome(false)
      listener.data must beSome(new Array[Byte](8))
    }

    "simple frame ack" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x06, // type
        Flags.ACK, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        // body
        0x00, 0x00, 0x00, 0x00, // opaque data
        0x00, 0x00, 0x00, 0x00
      )
      val listener = new PingListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.ack must beSome(true)
      listener.data must beSome(new Array[Byte](8))
    }

    "stream id != 0" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "too small" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }

    "too large" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }
  }

  "GOAWAY" >> {
    class GoAwayListener extends MockFrameListener(false) {
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

    "simple frame" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x07, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        // body
        0x00, 0x00, 0x00, 0x01, // last stream
        0x00, 0x00, 0x00, 0x00  // error code
      )
      val listener = new GoAwayListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.lastStream must beSome(1)
      listener.errorCode must beSome(0)
      listener.debugData must beSome(new Array[Byte](0))
    }

    "simple frame with data" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x09, // length
        0x07, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        // body
        0x00, 0x00, 0x00, 0x01, // last stream
        0x00, 0x00, 0x00, 0x00,  // error code
        0x01
      )
      val listener = new GoAwayListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.lastStream must beSome(1)
      listener.errorCode must beSome(0)
      listener.debugData must beSome(Array[Byte](0x01))
    }

    "streamid != 0" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x09, // length
        0x07, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x00, 0x00, 0x00, 0x01, // last stream
        0x00, 0x00, 0x00, 0x00,  // error code
        0x01
      )
      val listener = new GoAwayListener
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }
  }

  "WINDOW_UPDATE" >> {

    class WindowListener extends MockFrameListener(false) {
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

    "simple frame" >> {
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

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(0)
      listener.sizeIncrement must beSome(1)
    }

    "increment 0 on session" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "increment 0 on stream" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2StreamException(1, Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "too small" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }

    "too large" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.FRAME_SIZE_ERROR.code, _)) => ok
      }
    }
  }

  "CONTINUATION" >> {
    class ContinuationListener(inseq: Boolean) extends MockFrameListener(inseq) {
      var streamId: Option[Int] = None
      var endHeaders: Option[Boolean] = None
      var data: ByteBuffer = null
      override def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Result = {
        this.streamId = Some(streamId)
        this.endHeaders = Some(endHeaders)
        this.data = data
        Continue
      }
    }

    //  +---------------------------------------------------------------+
    //  |                   Header Block Fragment (*)                 ...
    //  +---------------------------------------------------------------+

    "simple frame" >> {
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

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.endHeaders must beSome(false)
      listener.data must_== buffer(0x01)
    }

    "streamId == 0" >> {
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

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }

    "end_headers" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x01, // length
        0x09, // type
        Flags.END_HEADERS, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x01 // increment
      )
      val listener = new ContinuationListener(true)
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must_== Continue
      listener.streamId must beSome(1)
      listener.endHeaders must beSome(true)
      listener.data must_== buffer(0x01)
    }

    "not in headers sequence" >> {
      // PUSH_PROMISE frame:
      val testData = buffer(
        0x00, 0x00, 0x01, // length
        0x09, // type
        Flags.END_HEADERS, // flags
        0x00, 0x00, 0x00, 0x01, // streamId
        // body
        0x01 // increment
      )
      val listener = new ContinuationListener(false)
      val dec = new FrameDecoder(Http2Settings.default, listener)

      dec.decodeBuffer(testData) must beLike {
        case Error(Http2SessionException(Http2Exception.PROTOCOL_ERROR.code, _)) => ok
      }
    }
  }

//  case FrameTypes.CONTINUATION  => decodeContinuationFrame(buffer, streamId, flags)

  "unknown frame types" >> {
    "pass the data to the extension frame method" in {

      // UNKONWN Frame:
      // Length: 8, Type: 0x16, Flags: 0, R: 0, StreamID: 0
      val testData = buffer(
        0x00, 0x00, 0x08, // length
        0x16, // type
        0x00, // flags
        0x00, 0x00, 0x00, 0x00, // streamId
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

      val listener = new MockFrameListener(false)
      val dec = new FrameDecoder(Http2Settings.default, listener) {
        var data: ByteBuffer = null
        var code: Option[Byte] = None
        var flags: Option[Byte] = None
        var streamId: Option[Int] = None

        override def onExtensionFrame(_code: Byte, _streamId: Int, _flags: Byte, buffer: ByteBuffer): Result = {
          data = buffer
          code = Some(_code)
          streamId = Some(_streamId)
          flags = Some(_flags)
          Continue
        }
      }

      dec.decodeBuffer(testData) must_== Continue
      dec.data must_== ByteBuffer.wrap(new Array[Byte](8))
      dec.code must_== Some(0x16)
      dec.flags must_== Some(0x00)
      dec.streamId must_== Some(0)
    }
  }
}
