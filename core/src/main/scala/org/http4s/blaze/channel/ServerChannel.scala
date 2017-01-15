package org.http4s.blaze.channel


import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.util.control.NonFatal

import org.log4s.getLogger

/** Representation of a bound server */
abstract class ServerChannel extends Closeable { self =>
  protected val logger = getLogger

  private var shutdownHooks = new AtomicReference[Vector[()=> Unit]](Vector.empty)

  /** Close out any resources associated with the [[ServerChannel]] */
  protected def closeChannel(): Unit

  /** Close the [[ServerChannel]] and execute any shutdown hooks */
  final def close(): Unit = {
    closeChannel()
    runShutdownHooks()
  }

  /** Wait for this server channel to close */
  final def join(): Unit = {
    val l = new CountDownLatch(1)

    if (!addShutdownHook(() => l.countDown()))
      l.countDown()

    l.await()
  }

  /** Add code to be executed when the [[ServerChannel]] is closed
    *
    * @param f hook to execute on shutdown
    * @return true if the hook was successfully registered, false otherwise
    */
  final def addShutdownHook(f: () => Unit): Boolean = {
    @tailrec
    def go(): Boolean = shutdownHooks.get() match {
      case null                                                     => false
      case hooks if(shutdownHooks.compareAndSet(hooks, hooks :+ f)) => true
      case _                                                        => go()
    }

    go()
  }

  private def runShutdownHooks(): Unit = {
    val hooks = shutdownHooks.getAndSet(null)
    if (hooks != null) {
      hooks.foreach { f =>
        try f()
        catch { case NonFatal(t) => logger.error(t)(s"Exception occurred during Channel shutdown.") }
      }
    }
  }

  /* Return the bound socket address for this server channel */
  def socketAddress: InetSocketAddress
}
