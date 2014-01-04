package pipeline

import scala.reflect.ClassTag
import scala.concurrent.Future

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
trait HeadStage[T] extends Stage[Nothing, T] {

  private[pipeline] var next: MiddleStage[T, _]

  protected val oclass: Class[T]

  final protected def iclass: Class[Nothing] = {
    sys.error("HeadStage doesn't have an inbound class")
  }

  def handleInbound(data: Nothing): Future[Unit] = {
    sys.error("HeadStage doesn't receive data directly")
  }

  def replaceInline(stage: Stage[Nothing, T]): stage.type = {
    sys.error("Cannot replace HeadStage")
  }

  private[pipeline] def prev: Stage[_, Nothing] = {
    sys.error("HeadStage doesn't have a previous node")
  }
}
