package org.http4s.blaze

import java.nio.ByteBuffer
import org.http4s.blaze.pipeline.LeafBuilder

package object channel {

  type BufferPipelineBuilder = SocketConnection => LeafBuilder[ByteBuffer]

}
