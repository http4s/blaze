package blaze.pipeline

import blaze.pipeline.PipelineBuilder.CapStage
import blaze.pipeline.Command.Command

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class PipelineBuilder[I, O]private[pipeline](headStage: HeadStage[I], tail: MidStage[_, O]) {

  def append[N](stage: MidStage[O, N]): PipelineBuilder[I, N] = {

    if (stage.prev != null) sys.error(s"Stage $stage must be fresh")

    tail.next = stage
    stage.prev = tail

    new PipelineBuilder(headStage, stage)
  }

  def cap(stage: TailStage[O]): HeadStage[I] = {

    if (stage.prev != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail.next = stage
    stage.prev = tail

    stage match {
      case m: MidStage[O, Any] if m.next == null =>
        m.next = new CapStage
      case _ =>   // NOOP
    }

    headStage
  }

  def prepend(stage: MidStage[I, I]): this.type = {
    headStage.spliceAfter(stage)
    this
  }
}

class RootBuilder[T](head: HeadStage[T]) extends PipelineBuilder[T, T](head, head)

object PipelineBuilder {


  private[pipeline] class CapStage extends TailStage[Any] {
    def name: String = "Capping stage"

    override def inboundCommand(cmd: Command): Unit = {
      logger.warn(s"Command $cmd reached a Capping Stage. Does the last " +
                   "stage of your pipeline properly handle all commands?")
    }
  }

}