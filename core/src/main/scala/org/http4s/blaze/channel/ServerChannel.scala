package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel
import com.typesafe.scalalogging.slf4j.LazyLogging

/**
 * @author Bryce Anderson
 *         Created on 1/23/14
 */


abstract class ServerChannel extends Runnable with LazyLogging {

  type C <: NetworkChannel

  protected def channel: C

  /** Starts the accept loop, handing connections off to a thread pool */
  def run(): Unit

  def close(): Unit = channel.close()

  def runAsync(): Thread = {
    logger.trace("Starting server loop on new thread")
    val t = new Thread(this)
    t.start()
    t
  }

}
