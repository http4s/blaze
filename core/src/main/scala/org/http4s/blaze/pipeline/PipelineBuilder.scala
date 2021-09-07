/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.pipeline

/** By requiring a LeafBuilder, you are ensuring that the pipeline is capped with a TailStage as the
  * only way to get a LeafBuilder if by capping with a TailStage or getting a new LeafBuilder from a
  * TailStage
  * @param leaf
  *   the capped pipeline
  * @tparam I
  *   type the pipeline will read and write
  */
final class LeafBuilder[I] private[pipeline] (leaf: Tail[I]) {
  def prepend[N](stage: MidStage[N, I]): LeafBuilder[N] = {
    if (stage._nextStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_]])
      sys.error("LeafBuilder cannot accept HeadStages!")

    leaf._prevStage = stage
    stage._nextStage = leaf
    new LeafBuilder[N](stage)
  }

  def prepend[N](tb: TrunkBuilder[N, I]): LeafBuilder[N] = tb.cap(this)

  def +:[N](tb: TrunkBuilder[N, I]): LeafBuilder[N] = prepend(tb)

  def base(root: HeadStage[I]): root.type = {
    if (root._nextStage != null) sys.error(s"Stage $root must be fresh")
    leaf._prevStage = root
    root._nextStage = leaf
    root
  }
}

object LeafBuilder {
  def apply[T](leaf: TailStage[T]): LeafBuilder[T] = new LeafBuilder[T](leaf)
}

/** Facilitates starting a pipeline from a MidStage. Can be appended and prepended to build up the
  * pipeline
  */
final class TrunkBuilder[I1, O] private[pipeline] (
    protected val head: MidStage[I1, _],
    protected val tail: MidStage[_, O]) {
  def append[N](stage: MidStage[O, N]): TrunkBuilder[I1, N] = {
    if (stage._prevStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_]])
      sys.error(s"Cannot append HeadStages: $stage")

    tail._nextStage = stage
    stage._prevStage = tail

    new TrunkBuilder(head, stage)
  }

  def :+[N](stage: MidStage[O, N]): TrunkBuilder[I1, N] = append(stage)

  def append[A](tb: TrunkBuilder[O, A]): TrunkBuilder[I1, A] = {
    append(tb.head)
    new TrunkBuilder(this.head, tb.tail)
  }

  def cap(stage: TailStage[O]): LeafBuilder[I1] = {
    if (stage._prevStage != null)
      sys.error(s"Stage $stage must be fresh")

    tail._nextStage = stage
    stage._prevStage = tail
    new LeafBuilder(head)
  }

  def cap(lb: LeafBuilder[O]): LeafBuilder[I1] = {
    lb.prepend(tail)
    new LeafBuilder(head)
  }

  def prepend[A](stage: MidStage[A, I1]): TrunkBuilder[A, O] = {
    if (stage._nextStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_]])
      sys.error("Cannot prepend HeadStage. Use method base")

    head._prevStage = stage
    stage._nextStage = head
    new TrunkBuilder(stage, tail)
  }

  def prepend[A1, A2](tb: TrunkBuilder[A1, I1]): TrunkBuilder[A1, O] =
    tb.append(this)
}

object TrunkBuilder {
  def apply[T1, T2](mid: MidStage[T1, T2]): TrunkBuilder[T1, T2] =
    new TrunkBuilder(mid, mid)
}
