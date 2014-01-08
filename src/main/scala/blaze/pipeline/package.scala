package blaze

import java.nio.ByteBuffer
import scala.concurrent.Future

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */


package object pipeline {

  type RootBuilder[T] = PipelineBuilder[T, T]

}
