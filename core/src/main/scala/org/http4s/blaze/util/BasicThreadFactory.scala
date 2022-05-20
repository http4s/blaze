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

package org.http4s.blaze.util

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

private[blaze] object BasicThreadFactory {

  /** Construct a basic `ThreadFactory`
    *
    * Resulting threads are named with their prefix plus '-unique_id' where unique id is an
    * increasing integer.
    */
  def apply(prefix: String, daemonThreads: Boolean): ThreadFactory =
    new Impl(prefix, daemonThreads)

  private[this] class Impl(prefix: String, daemonThreads: Boolean) extends ThreadFactory {
    private[this] val next = new AtomicInteger(0)
    override def newThread(r: Runnable): Thread = {
      val id = next.getAndIncrement()
      val thread = new Thread(r, s"$prefix-$id")
      thread.setDaemon(daemonThreads)
      thread
    }
  }
}
