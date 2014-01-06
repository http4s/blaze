package blaze.pipeline

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class PipelineBuilder[I, O]private[pipeline](headStage: HeadStage[I], tail: MidStage[_, O]) {

  def addLast[N](stage: MidStage[O, N]): PipelineBuilder[I, N] = {

    if (stage.prev != null) {
      sys.error(s"Stage $stage must be fresh")
    }

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

    headStage
  }

  def addFirst(stage: MidStage[I, I]): this.type = {
    headStage.spliceAfter(stage)
    this
  }

}

class RootBuilder[T](head: HeadStage[T]) extends PipelineBuilder[T, T](head, head)

object PipelineBuilder {

  //def build

}