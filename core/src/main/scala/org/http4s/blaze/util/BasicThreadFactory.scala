/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.util

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

private[blaze] object BasicThreadFactory {

  /** Construct a basic `ThreadFactory`
    *
    * Resulting threads are named with their prefix plus '-unique_id'
    * where unique id is an increasing integer.
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
