package pipeline

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class PipelineBuilder[I, O]private[pipeline](headStage: HeadStage[I], tail: Stage[_, O]) {

  def result: HeadStage[I] = headStage

  def addLast[N](stage: Stage[O, N]): PipelineBuilder[I, N] = {

    if (stage.prev != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail.next = stage
    stage.prev = tail

    new PipelineBuilder(headStage, stage)
  }

  def addFirst(stage: Stage[I, I]): this.type = {
    headStage.spliceAfter(stage)
    this
  }

}

class RootBuilder[T](head: HeadStage[T]) extends PipelineBuilder[T, T](head, head)

object PipelineBuilder {

  //def build

}