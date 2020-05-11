/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import scala.collection.mutable
import scala.concurrent.ExecutionContext

class DelayedExecutionContext extends ExecutionContext {
  private[this] val pending = new mutable.Queue[Runnable]()

  def executeOne(): Unit =
    if (!pending.isEmpty) pending.dequeue().run()

  def executeAll(): Unit =
    while (!pending.isEmpty) executeOne()

  override def execute(runnable: Runnable): Unit = {
    pending += runnable
    ()
  }
  override def reportFailure(cause: Throwable): Unit = throw cause
}
