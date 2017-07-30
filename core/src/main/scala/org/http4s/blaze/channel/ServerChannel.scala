package org.http4s.blaze.channel

import java.io.Closeable
import java.net.InetSocketAddress
import org.log4s.getLogger
import org.http4s.blaze.util.Execution
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext

/** Representation of a bound server */
abstract class ServerChannel extends Closeable { self =>
  import ServerChannel.State._

  protected val logger = getLogger

  private var state: State = Open

  private val shutdownHooks = new scala.collection.mutable.Queue[Hook]

  /** Close out any resources associated with the [[ServerChannel]] */
  protected def closeChannel(): Unit

  /** The bound socket address for this [[ServerChannel]] */
  def socketAddress: InetSocketAddress

  /** Close the [[ServerChannel]] and execute any shutdown hooks
    *
    * @note this method is idempotent
    */
  final def close(): Unit = {
    val run = shutdownHooks.synchronized {
      if (state != Open) false
      else {
        state = Closing
        // Need to close the channel, get all the hooks, then notify the listeners
        closeChannel()
        true
      }
    }

    // We move this outside of the lock to ensure that we don't schedule work
    // inside the lock since the EC could run the task immediately.
    if (run) scheduleHook()
  }

  /** Wait for this server channel to close, including execution of all successfully
    * registered shutdown hooks.
    */
  final def join(): Unit = shutdownHooks.synchronized {
    while (state != Closed) {
      shutdownHooks.wait()
    }
  }

  /** Add code to be executed when the [[ServerChannel]] is closed
    *
    * @param f hook to execute on shutdown
    * @return true if the hook was successfully registered, false otherwise.
    */
  final def addShutdownHook(f: () => Unit)(implicit ec: ExecutionContext = Execution.directec): Boolean = {
    shutdownHooks.synchronized {
      if (state != Open) false
      else {
        shutdownHooks += Hook(f, ec)
        true
      }
    }
  }

  // Checks if we have a hook, and if we do, schedule it and then check again
  private[this] def scheduleHook(): Unit = {
    val hook = shutdownHooks.synchronized {
      if (shutdownHooks.nonEmpty) Some(shutdownHooks.dequeue())
      else {
        // all our hooks are done! Switch to closed state, notify
        // all the listeners
        state = Closed
        shutdownHooks.notifyAll()
        None
      }
    }
    hook match {
      case Some(hook) => hook.ec.execute(new HookRunnable(hook.task))
      case None => ()
    }
  }

  private case class Hook(task: () => Unit, ec: ExecutionContext)

  // Bundles the task of executing a hook and scheduling the next hook
  private[this] class HookRunnable(hook: () => Unit) extends Runnable {
    override def run(): Unit = {
      try hook()
      catch {
        case NonFatal(t) =>
          logger.error(t)(s"Exception occurred during Channel shutdown.")
      }
      finally {
        // We bounce these through the thread local trampoline to ensure
        // that we don't get a SOE if everyone is using the direct EC.
        Execution.trampoline.execute(new Runnable {
          override def run(): Unit = scheduleHook()
        })
      }
    }
  }
}

private object ServerChannel {
  object State extends Enumeration {
    type State = Value
    val Open, Closing, Closed = Value
  }
}
