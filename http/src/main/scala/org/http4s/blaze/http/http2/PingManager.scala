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

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import org.log4s.getLogger

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration

private class PingManager(session: SessionCore) {
  import PingManager._

  private[this] val logger = getLogger
  private[this] var state: State = Idle

  // Must be called from within the sessionExecutor
  def ping(): Future[Duration] = {
    val time = System.currentTimeMillis
    state match {
      case Idle =>
        val data = new Array[Byte](8)
        ByteBuffer.wrap(data).putLong(time)
        val pingFrame = session.http2Encoder.pingFrame(data)
        // TODO: we can do a lot of cool things with pings by managing when they are written
        if (session.writeController.write(pingFrame)) {
          logger.debug(s"PING initiated at $time")
          val p = Promise[Duration]()
          state = Pinging(time, p)
          p.future
        } else {
          val ex = new Exception("Socket closed")
          state = Closed(ex)
          Future.failed(ex)
        }

      case Pinging(_, _) =>
        val ex = new IllegalStateException("Ping already in progress")
        Future.failed(ex)

      case Closed(ex) =>
        Future.failed(ex)
    }
  }

  def pingAckReceived(data: Array[Byte]): Unit =
    state match {
      case Pinging(sent, continuation) =>
        state = Idle

        if (ByteBuffer
            .wrap(data)
            .getLong != sent) { // data guaranteed to be 8 bytes
          val msg = "Received ping response with unknown data."
          val ex = new Exception(msg)
          logger.warn(ex)(msg)
          continuation.tryFailure(ex)
          ()
        } else {
          val duration =
            Duration.create(math.max(0, System.currentTimeMillis - sent), TimeUnit.MILLISECONDS)
          logger.debug(s"Ping duration: $duration")
          continuation.trySuccess(duration)
          ()
        }

      case other => // nop
        logger.debug(s"Ping ACKed in state $other")
    }

  def close(): Unit =
    state match {
      case Closed(_) =>
        () // nop

      case Idle =>
        state = GracefulClosed

      case Pinging(_, p) =>
        state = GracefulClosed
        p.failure(new Exception("PING interrupted"))
        ()
    }
}

private object PingManager {
  private sealed trait State

  private case object Idle extends State
  private case class Pinging(started: Long, p: Promise[Duration]) extends State
  private case class Closed(ex: Exception) extends State

  private val GracefulClosed = Closed(new Exception("Ping manger shut down"))
}
