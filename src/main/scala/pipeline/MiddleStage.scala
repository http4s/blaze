package pipeline

import scala.reflect.ClassTag

import Command._

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
trait MiddleStage[I, O] extends Stage[I, O] {

  private[pipeline] var prev: Stage[_, I]
  private[pipeline] var next: Stage[O, _]

  def replaceInline(stage: Stage[I, O]): stage.type = {
    cleanup()
    prev.next = stage
    next.prev = stage
    stage
  }
}
