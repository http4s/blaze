package org.http4s.blaze

import java.nio.ByteBuffer
import org.http4s.blaze.pipeline.LeafBuilder

package object channel {

  type BufferPipelineBuilder = SocketConnection => LeafBuilder[ByteBuffer]

  /** Default number of threads used to make a new
    * [[org.http4s.blaze.channel.nio1.SelectorLoopPool]] if not specified
    * */
  val DefaultPoolSize: Int =
    math.max(4, Runtime.getRuntime.availableProcessors() + 1)
}
