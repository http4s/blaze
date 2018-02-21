package org.http4s.blaze.channel.nio1

import java.nio.channels.Selector
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec

/** Provides a fixed size pool of [[SelectorLoop]]s, distributing work in a round robin fashion */
final class FixedSelectorPool(
    poolSize: Int,
    bufferSize: Int,
    threadFactory: ThreadFactory
) extends SelectorLoopPool {
  require(poolSize > 0, s"Invalid pool size: $poolSize")

  private[this] val next = new AtomicLong(0L)
  private[this] val loops = Array.fill(poolSize) {
    new SelectorLoop(Selector.open(), bufferSize, threadFactory)
  }

  @tailrec
  def nextLoop(): SelectorLoop = {
    val i = next.get
    if (next.compareAndSet(i, (i + 1L) % poolSize)) loops(i.toInt)
    else nextLoop()
  }

  def close(): Unit = loops.foreach(_.close())
}
