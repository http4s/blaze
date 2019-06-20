package org.http4s.blaze.util

private[blaze] final class ImmutableArray[A] private (private[this] val underlying: Array[A])
    extends Seq[A] {
  def iterator: Iterator[A] = underlying.iterator
  def apply(i: Int): A = underlying(i)
  def length: Int = underlying.length
}

private[blaze] object ImmutableArray {
  def apply[A](underlying: Array[A]): ImmutableArray[A] =
    new ImmutableArray(underlying)
}
