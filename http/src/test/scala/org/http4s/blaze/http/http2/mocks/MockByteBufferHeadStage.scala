package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer
import org.http4s.blaze.util.BufferTools

private[http2] class MockByteBufferHeadStage extends MockHeadStage[ByteBuffer] {
  override def name: String = "Head"

  def consumeOutboundByteBuf(): ByteBuffer =
    BufferTools.joinBuffers(consumeOutboundData())
}
