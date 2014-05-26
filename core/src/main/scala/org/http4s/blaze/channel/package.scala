package org.http4s.blaze

import java.nio.ByteBuffer
import org.http4s.blaze.pipeline.LeafBuilder

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
package object channel {
    type BufferPipelineBuilder = SocketConnection => LeafBuilder[ByteBuffer]
}
