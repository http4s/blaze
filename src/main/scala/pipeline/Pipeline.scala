package pipeline

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class Pipeline[I, O](headStage: HeadStage[I], tail: Stage[_, O]) {


  def addLast[N](stage: Stage[O, N]): Pipeline[I, N] = {

    if (stage.prev != null || stage.next != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail.next = stage

    ???
  }

}

private class RootPipeline[T](head: HeadStage[T]) extends Pipeline[T, T](head, head)

object Pipeline {

  //def build

}