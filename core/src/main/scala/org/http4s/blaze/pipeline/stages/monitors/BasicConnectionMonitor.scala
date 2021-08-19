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

package org.http4s.blaze.pipeline.stages.monitors

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

/** Simple connection information monitor This monitor provides only the most basic connection
  * information: inbound and outbound bytes and live connections
  */
class BasicConnectionMonitor extends ConnectionMonitor {
  private val inboundBytes = new AtomicLong(0)
  private val outboundBytes = new AtomicLong(0)
  private val connections = new AtomicInteger(0)

  override protected def connectionAccepted(): Unit = {
    connections.incrementAndGet()
    ()
  }
  override protected def connectionClosed(): Unit = {
    connections.decrementAndGet()
    ()
  }
  override protected def bytesInbound(n: Long): Unit = {
    inboundBytes.addAndGet(n)
    ()
  }
  override protected def bytesOutBound(n: Long): Unit = {
    outboundBytes.addAndGet(n)
    ()
  }

  /** Get the inbound bytes, outbound bytes, and the number of live connections */
  def getStatus(): (Long, Long, Int) =
    (inboundBytes.get, outboundBytes.get, connections.get)
}
