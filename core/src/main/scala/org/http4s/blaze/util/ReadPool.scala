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

import java.util

import org.http4s.blaze.pipeline.Command.EOF

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}

/** Tool for enqueuing inbound data */
private[blaze] class ReadPool[T] {
  private[this] var closeT: Throwable = null
  private[this] var readP: Promise[T] = null
  private[this] val offerQ = new util.ArrayDeque[T]()

  /** Useful for tracking when a message was consumed */
  def messageConsumed(t: T): Unit = {
    val _ = t
  }

  final def queuedMessages: Int = offerQ.size

  final def closed: Boolean = closeT != null

  final def readInto(p: Promise[T]): Unit =
    if (readP != null) {
      p.tryFailure(new IllegalStateException("Multiple pending read requests"))
      ()
    } else if (!offerQ.isEmpty) {
      val m = offerQ.poll()
      messageConsumed(m)
      p.trySuccess(m)
      ()
    } else if (closeT != null) {
      p.tryFailure(closeT)
      ()
    } else readP = p

  final def read(): Future[T] =
    // optimization for the case of existing data
    if (!offerQ.isEmpty) {
      val m = offerQ.poll()
      messageConsumed(m)
      Future.successful(m)
    } else {
      val p = Promise[T]()
      readInto(p)
      p.future
    }

  final def close(t: Throwable = EOF): Unit =
    if (closeT == null) {
      closeT = t
      if (readP != null) {
        val p = readP
        readP = null
        p.tryFailure(t)
        ()
      }
    }

  final def closeAndClear(t: Throwable = EOF): Seq[T] = {
    val b = new ListBuffer[T]
    while (!offerQ.isEmpty)
      b += offerQ.poll()
    close(t)
    b.result()
  }

  final def offer(t: T): Boolean =
    if (closeT != null) false
    else if (readP != null) {
      val p = readP
      readP = null
      messageConsumed(t)
      p.trySuccess(t)
    } else offerQ.offer(t)
}
