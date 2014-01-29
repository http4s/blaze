package blaze.pipeline

import blaze.pipeline.Command.Command

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */

final class RootBuilder[I, O] private[pipeline](headStage: HeadStage[I], tail: MidStage[_, O]) {

  def append[N](stage: MidStage[O, N]): RootBuilder[I, N] = {
    if (stage._prevStage != null) sys.error(s"Stage $stage must be fresh")

    tail._nextStage = stage
    stage._prevStage = tail

    new RootBuilder(headStage, stage)
  }

  def cap(stage: TailStage[O]): HeadStage[I] = {
    if (stage._prevStage != null)
      sys.error(s"Stage $stage must be fresh")
    
    tail._nextStage = stage
    stage._prevStage = tail

    headStage
  }

  def prepend(stage: MidStage[I, I]): this.type = {
    if (stage._prevStage != null)
      sys.error(s"Stage $stage must be fresh!")
    
    headStage.spliceAfter(stage)
    this
  }
}

final class LeafBuilder[I] private[pipeline](val leaf: BaseStage[I]) {
  
  def prepend[N](stage: MidStage[N, I]): LeafBuilder[N] = {
    leaf._prevStage = stage
    stage._nextStage = leaf
    new LeafBuilder[N](stage)
  }
  
  def base(root: HeadStage[I]): root.type = {
    if (root._nextStage != null) sys.error(s"Stage $root must be fresh")
    leaf._prevStage = root
    root._nextStage = leaf
    root    
  }
}

final class TrunkBuilder[I1, I2, O] private[pipeline](head: MidStage[I1, I2], tail: MidStage[_, O]) {

  def append[N](stage: MidStage[O, N]): TrunkBuilder[I1, I2, N] = {
    if (stage._prevStage != null) sys.error(s"Stage $stage must be fresh")

    tail._nextStage = stage
    stage._prevStage = tail

    new TrunkBuilder(head, stage)
  }

  def cap[T](stage: TailStage[O]): LeafBuilder[I1] = {
    if (stage._prevStage != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail._nextStage = stage
    stage._prevStage = tail
    new LeafBuilder(head)
  }
  
  def base(root: HeadStage[I1]): RootBuilder[I1, O] = {
    if (root._nextStage != null) {
      sys.error(s"Stage $root must be fresh")
    }
    
    head._prevStage = root
    root._nextStage = head
    new RootBuilder(root, tail)
  }

  def prepend(stage: HeadStage[I1]): RootBuilder[I1, O] = {
    head._prevStage = stage
    stage._nextStage = head
    new RootBuilder(stage, tail)
  }

  def prepend[A](stage: MidStage[A, I1]): TrunkBuilder[A, I1, O] = {
    head._prevStage = stage
    stage._nextStage = head
    new TrunkBuilder(stage, tail)
  }
}

object PipelineBuilder {

  def apply[T](head: HeadStage[T]): RootBuilder[T, T] = new RootBuilder(head, head)
  
  def apply[T](leaf: TailStage[T]): LeafBuilder[T] = new LeafBuilder[T](leaf)

  def apply[T1, T2](mid: MidStage[T1, T2]): TrunkBuilder[T1, T2, T2] = new TrunkBuilder(mid, mid)
}
