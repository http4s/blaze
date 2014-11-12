package org.http4s.blaze.channel.nio1

import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicInteger


trait SelectorLoopPool {

  def nextLoop(): SelectorLoop
  
  def shutdown(): Unit

}

class FixedArraySelectorPool(poolSize: Int, bufferSize: Int) extends SelectorLoopPool {

  private val loops = 0.until(poolSize).map{ _ =>
    val l = new SelectorLoop(Selector.open(), bufferSize)
    l.start()
    l
  }.toArray

  private val _nextLoop = new AtomicInteger(0)

  def nextLoop(): SelectorLoop = {
    val l = math.abs(_nextLoop.incrementAndGet() % poolSize)
    loops(l)
  }

  def shutdown(): Unit = loops.foreach(_.close())
}
