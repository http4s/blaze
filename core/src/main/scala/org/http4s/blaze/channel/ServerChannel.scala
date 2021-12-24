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

package org.http4s.blaze.channel

import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.log4s.getLogger
import org.http4s.blaze.util.Execution
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext

/** Representation of a bound server */
abstract class ServerChannel extends Closeable { self =>
  import ServerChannel._

  protected val logger = getLogger

  private var state: State = Open

  private val shutdownHooks = Vector.newBuilder[Hook]

  /** Close out any resources associated with the [[ServerChannel]]
    *
    * @note
    *   Regardless of the number of times `close()` is called this method will only be called once.
    */
  protected def closeChannel(): Unit

  /** The bound socket address for this [[ServerChannel]] */
  def socketAddress: InetSocketAddress

  /** Close the [[ServerChannel]] and execute any shutdown hooks
    *
    * @note
    *   this method is idempotent
    */
  final def close(): Unit = {
    val hooks = shutdownHooks.synchronized {
      if (state != Open) Vector.empty
      else {
        state = Closing
        // Need to close the channel, get all the hooks, then notify the listeners
        closeChannel()
        val hooks = shutdownHooks.result()
        shutdownHooks.clear()
        hooks
      }
    }

    scheduleHooks(hooks)
  }

  /** Wait for this server channel to close, including execution of all successfully registered
    * shutdown hooks.
    */
  final def join(): Unit =
    shutdownHooks.synchronized {
      while (state != Closed)
        shutdownHooks.wait()
    }

  /** Add code to be executed when the [[ServerChannel]] is closed
    *
    * @note
    *   There are no guarantees as to order of shutdown hook execution or that they will be executed
    *   sequentially.
    *
    * @param f
    *   hook to execute on shutdown
    * @return
    *   true if the hook was successfully registered, false otherwise.
    */
  final def addShutdownHook(f: () => Unit)(implicit
      ec: ExecutionContext = Execution.directec): Boolean =
    shutdownHooks.synchronized {
      if (state != Open) false
      else {
        shutdownHooks += Hook(f, ec)
        true
      }
    }

  // Schedules all the shutdown hooks.
  private[this] def scheduleHooks(hooks: Vector[Hook]): Unit =
    // Its important that this calls `closeAndNotify` even if
    // there are no hooks to execute.
    if (hooks.isEmpty) closeAndNotify()
    else {
      val countdown = new AtomicInteger(hooks.size)
      hooks.foreach(hook => hook.ec.execute(new HookRunnable(countdown, hook.task)))
    }

  // set the terminal state and notify any callers of `join`.
  // This method should be idempotent.
  private[this] def closeAndNotify(): Unit =
    shutdownHooks.synchronized {
      state = Closed
      shutdownHooks.notifyAll()
    }

  private[this] case class Hook(task: () => Unit, ec: ExecutionContext)

  // Bundles the task of executing a hook and scheduling the next hook
  private[this] class HookRunnable(countdown: AtomicInteger, hook: () => Unit) extends Runnable {
    override def run(): Unit =
      try hook()
      catch {
        case NonFatal(t) =>
          logger.error(t)(s"Exception occurred during Channel shutdown.")
      } finally
        // If we're the last hook to run, we notify any listeners
        if (countdown.decrementAndGet() == 0)
          closeAndNotify()
  }
}

private object ServerChannel {
  sealed trait State
  case object Open extends State
  case object Closing extends State
  case object Closed extends State
}
