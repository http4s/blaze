package blaze.pipeline.stages

import blaze.pipeline.HeadStage
import scala.concurrent.Future

/**
 * @author Bryce Anderson
 *         Created on 1/16/14
 */
class HoldingHead[O](var in: O) extends HeadStage[O] {
  var received: O = _

  private var done = false
  def readRequest(size: Int): Future[O] = {
    if (!done) {
      done = true
      Future.successful(in)
    }
    else Future.failed(new IndexOutOfBoundsException)
  }

  def writeRequest(data: O): Future[Any] = {
    received = data
    Future.successful()
  }

  def name: String = "HoldingHead"
}
