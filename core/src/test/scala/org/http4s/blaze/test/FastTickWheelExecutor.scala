/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.test
import org.http4s.blaze.util.TickWheelExecutor
import org.specs2.mutable.After

import scala.concurrent.duration.DurationLong

trait FastTickWheelExecutor extends After {
  // The default TickWheelExecutor has 200ms ticks. It should be acceptable for most real world use cases.
  // If one needs very short timeouts (like we do in tests), providing a custom TickWheelExecutor is a solution.
  val scheduler: TickWheelExecutor = new TickWheelExecutor(tick = 10.millis)
  def after: Unit = {
    scheduler.shutdown()
    ()
  }
}
