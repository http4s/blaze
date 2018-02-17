package org.http4s.blaze.channel.nio1

import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicInteger

/** Provides a fixed size pool of [[SelectorLoop]]s, distributing work in a round robin fashion */
class FixedSelectorPool(poolSize: Int, bufferSize: Int) extends SelectorLoopPool {

  private val loops = 0
    .until(poolSize)
    .map { id =>
      val l = new SelectorLoop(s"blaze-nio-fixed-selector-pool-$id", Selector.open(), bufferSize)
      l.setDaemon(true)
      l.start()
      l
    }
    .toArray

  private val _nextLoop = new AtomicInteger(0)

  def nextLoop(): SelectorLoop = {
    val l = math.abs(_nextLoop.incrementAndGet() % poolSize)
    loops(l)
  }

  def shutdown(): Unit = loops.foreach(_.close())
}
