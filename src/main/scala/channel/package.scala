import java.nio.ByteBuffer
import pipeline.{RootBuilder, HeadStage}

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
package object channel {

  type PipeFactory = RootBuilder[ByteBuffer] => HeadStage[ByteBuffer]

}
