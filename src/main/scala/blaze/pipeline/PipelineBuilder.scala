package blaze.pipeline

import blaze.pipeline.Command.Command

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class PipelineBuilder[I, O] private[pipeline](headStage: HeadStage[I], tail: MidStage[_, O]) {

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
      case m: MidStage[O, _] if m.next == null =>
        m.next = PipelineBuilder.capStage
      case _ =>   // NOOP
    }

    headStage
  }

  def prepend(stage: MidStage[I, I]): this.type = {
    headStage.spliceAfter(stage)
    this
  }
}

class Segment[I1, I2, O] private[pipeline](head: MidStage[I1, I2], tail: MidStage[_, O]) {

  def append[N](stage: MidStage[O, N]): Segment[I1, I2, N] = {
    if (stage.prev != null) sys.error(s"Stage $stage must be fresh")

    tail.next = stage
    stage.prev = tail

    new Segment(head, stage)
  }

  def cap(stage: TailStage[O]): MidStage[I1, I2] = {

    if (stage.prev != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail.next = stage
    stage.prev = tail

    stage match {
      case m: MidStage[O, _] if m.next == null =>
        m.next = PipelineBuilder.capStage
      case _ =>   // NOOP
    }

    head
  }

  def prepend(stage: HeadStage[I1]): PipelineBuilder[I1, O] = {
    head.prev = stage
    stage.next = head
    new PipelineBuilder(stage, tail)
  }

  def prepend[A](stage: MidStage[A, I1]): Segment[A, I1, O] = {
    head.prev = stage
    stage.next = head
    new Segment(stage, tail)
  }
}

object PipelineBuilder {

  def apply[T](head: HeadStage[T]): RootBuilder[T] = new PipelineBuilder(head, head)

  def apply[T1, T2](mid: MidStage[T1, T2]): RootSegment[T1, T2] = new Segment(mid, mid)

  // Allows for the proper type inference
  private [pipeline] def capStage[T] = new CapStage().asInstanceOf[TailStage[T]]

  private[pipeline] class CapStage extends TailStage[Any] {
    def name: String = "Capping stage"

    override def inboundCommand(cmd: Command): Unit = {
      logger.warn(s"Command $cmd reached a Capping Stage. Does the last " +
                   "stage of your pipeline properly handle all commands?")
    }
  }
}