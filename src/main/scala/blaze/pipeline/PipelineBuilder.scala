package blaze.pipeline

import blaze.pipeline.Command.Command

/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */

///** Used to start forming a pipeline from a HeadStage. */
//final class RootBuilder[I, O] private[pipeline](headStage: HeadStage[I], tail: MidStage[_, O]) {
//
//  def append[N](stage: MidStage[O, N]): RootBuilder[I, N] = {
//    if (stage._prevStage != null) sys.error(s"Stage $stage must be fresh")
//
//    tail._nextStage = stage
//    stage._prevStage = tail
//
//    new RootBuilder(headStage, stage)
//  }
//
//  def append[I1, I2](tb: TrunkBuilder[O, I2]): RootBuilder[I, I2] = {
//    tb.prepend(tail)
//    new RootBuilder(headStage, tb.tail)
//  }
//
//  def cap(stage: TailStage[O]): HeadStage[I] = {
//    if (stage._prevStage != null)
//      sys.error(s"Stage $stage must be fresh")
//
//    tail._nextStage = stage
//    stage._prevStage = tail
//
//    headStage
//  }
//
//  def cap(lb: LeafBuilder[O]): lb.type = {
//    lb.leaf match {
//      case l: TailStage[O] => cap(l)
//      case m: MidStage[O, _] => append(m)
//      case e => sys.error("Contaminated LeafBuilder has HeadStage: " + e)
//    }
//    lb
//  }
//
//  def prepend(stage: MidStage[I, I]): this.type = {
//    if (stage._prevStage != null)
//      sys.error(s"Stage $stage must be fresh!")
//
//    headStage.spliceAfter(stage)
//    this
//  }
//}

/** By requiring a LeafBuilder, you are ensuring that the pipeline is capped
  * with a TailStage as the only way to get a LeafBuilder if by capping with a
  * TailStage or getting a new LeafBuilder from a TailStage
  * @param leaf the capped pipeline
  * @tparam I type the pipeline will read and write
  */
final class LeafBuilder[I] private[pipeline](private[pipeline] val leaf: Tail[I]) {
  
  def prepend[N](stage: MidStage[N, I]): LeafBuilder[N] = {
    if (stage._nextStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_]]) sys.error("LeafBuilder cannot accept HeadStages!")

    leaf._prevStage = stage
    stage._nextStage = leaf
    new LeafBuilder[N](stage)
  }

  def prepend[N](tb: TrunkBuilder[N, I]): LeafBuilder[N] = tb.cap(this)
  
  def base(root: HeadStage[I]): root.type = {
    if (root._nextStage != null) sys.error(s"Stage $root must be fresh")
    leaf._prevStage = root
    root._nextStage = leaf
    root    
  }

//  def base(rb: RootBuilder[_, I]): rb.type = {
//    rb.cap(this)
//    rb
//  }
}

/** Facilitates starting a pipeline from a MidStage. Can be appended and prepended
  * to build up the pipeline
  */
final class TrunkBuilder[I1, O] private[pipeline](
                                protected val head: MidStage[I1, _],
                                private[pipeline] val tail: MidStage[_, O]) {

  def append[N](stage: MidStage[O, N]): TrunkBuilder[I1, N] = {
    if (stage._prevStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_]]) sys.error("Cannot append HeadStages: $stage")

    tail._nextStage = stage
    stage._prevStage = tail

    new TrunkBuilder(head, stage)
  }

  def append[A](tb: TrunkBuilder[O, A]): TrunkBuilder[I1, A] = {
    append(tb.head)
    new TrunkBuilder(this.head, tb.tail)
  }

  def cap(stage: TailStage[O]): LeafBuilder[I1] = {
    if (stage._prevStage != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail._nextStage = stage
    stage._prevStage = tail
    new LeafBuilder(head)
  }

  def cap(lb: LeafBuilder[O]): LeafBuilder[I1] = {
    lb.prepend(tail)
    new LeafBuilder(head)
  }
  
//  def base(root: HeadStage[I1]): RootBuilder[I1, O] = {
//    if (root._nextStage != null) {
//      sys.error(s"Stage $root must be fresh")
//    }
//
//    head._prevStage = root
//    root._nextStage = head
//    new RootBuilder(root, tail)
//  }
//
//  def base[A](rb: RootBuilder[A, I1]): RootBuilder[A, O] = rb.append(this)
//
//  def prepend(stage: HeadStage[I1]): RootBuilder[I1, O] = {
//    head._prevStage = stage
//    stage._nextStage = head
//    new RootBuilder(stage, tail)
//  }

  def prepend[A](stage: MidStage[A, I1]): TrunkBuilder[A, O] = {
    if (stage._nextStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_]]) sys.error("Cannot prepend HeadStage. Use method base")

    head._prevStage = stage
    stage._nextStage = head
    new TrunkBuilder(stage, tail)
  }

  def prepend[A1, A2](tb: TrunkBuilder[A1, I1]): TrunkBuilder[A1, O] = {
    tb.append(this)
  }
}

object PipelineBuilder {

//  def apply[T](head: HeadStage[T]): RootBuilder[T, T] = new RootBuilder(head, head)
  
  def apply[T](leaf: TailStage[T]): LeafBuilder[T] = new LeafBuilder[T](leaf)

  def apply[T1, T2](mid: MidStage[T1, T2]): TrunkBuilder[T1, T2] = new TrunkBuilder(mid, mid)
}

object LeafBuilder {
  def apply[T](leaf: TailStage[T]): LeafBuilder[T] = PipelineBuilder(leaf)
}

object TrunkBuilder {
  def apply[T1, T2](mid: MidStage[T1, T2]): TrunkBuilder[T1, T2] = PipelineBuilder(mid)
}
