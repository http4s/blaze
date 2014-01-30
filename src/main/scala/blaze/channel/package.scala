package blaze

import java.nio.ByteBuffer
import blaze.pipeline.{LeafBuilder, RootBuilder, HeadStage}

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
package object channel {

//  type PipeFactory = RootBuilder[ByteBuffer, ByteBuffer] => HeadStage[ByteBuffer]
    type BufferPipeline = () => LeafBuilder[ByteBuffer]
}
