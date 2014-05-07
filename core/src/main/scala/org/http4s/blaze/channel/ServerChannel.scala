package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel
import com.typesafe.scalalogging.slf4j.LazyLogging
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * @author Bryce Anderson
 *         Created on 1/23/14
 */


abstract class ServerChannel extends Runnable with LazyLogging with Closeable { self =>

  private val shutdownHooks = new AtomicReference[Vector[()=> Unit]](Vector.empty)

  type C <: NetworkChannel

  protected def channel: C

  /** Starts the accept loop, handing connections off to a thread pool */
  def run(): Unit

  def close(): Unit = {
    channel.close()
    runShutdownHooks()
  }

  final def addShutdownHook(f: () => Unit) {
    @tailrec
    def go(): Unit = {
      val hooks = shutdownHooks.get()
      if (hooks == null) sys.error("Channel appears to already be shut down!")
      if(!shutdownHooks.compareAndSet(hooks, hooks:+f)) go()
    }
    go()
  }


  final protected def runShutdownHooks(): Unit = {
    val hooks = shutdownHooks.getAndSet(null)
    if (hooks != null) {
      var exceptions = Vector.empty[Throwable]
      hooks.foreach { f =>
        try f()
        catch { case NonFatal(t) => exceptions:+= t }
      }

      // if there was an exception, rethrow them
      if (!exceptions.isEmpty) {
        sys.error(s"Exceptions occurred during Channel shutdown: ${exceptions.map(_.getStackTraceString)}")
      }
    }
  }

  def runAsync(): Thread = {
    logger.trace("Starting server loop on new thread")
    val t = new Thread(this)
    t.start()
    t
  }

}
