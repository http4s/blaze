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
