package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

trait HeaderHttp20Encoder { self: Http20FrameEncoder =>
  /** The type of the Headers that will be encoded */
  type Headers

  // The encoder used to compress the headers
  protected val headerEncoder: HeaderEncoder[Headers]

  final def setEncoderMaxTable(max: Int): Unit = { headerEncoder.setMaxTableSize(max) }

  final def mkHeaderFrame(streamId: Int,
                          priority: Option[Priority],
                       end_headers: Boolean,
                        end_stream: Boolean,
                           padding: Int,
                           headers: Headers): Seq[ByteBuffer] = {

    val buffer = headerEncoder.encodeHeaders(headers)

    mkHeaderFrame(buffer, streamId, priority, end_headers, end_stream, padding)
  }

  final def mkPushPromiseFrame(streamId: Int,
                              promiseId: Int,
                            end_headers: Boolean,
                                padding: Int,
                                headers: Headers): Seq[ByteBuffer] = {
    val buffer = headerEncoder.encodeHeaders(headers)
    mkPushPromiseFrame(streamId, promiseId, end_headers, padding, buffer)
  }

  final def mkContinuationFrame(streamId: Int, end_headers: Boolean, headers: Headers): Seq[ByteBuffer] = {
    val buffer = headerEncoder.encodeHeaders(headers)
    mkContinuationFrame(streamId, end_headers, buffer)
  }
}


