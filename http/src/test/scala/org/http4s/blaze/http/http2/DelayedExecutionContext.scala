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
