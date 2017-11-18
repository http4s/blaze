package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import scala.concurrent.Promise
import scala.concurrent.duration.Duration

private class PingManager(session: SessionCore) {

  private[this] case class PingState(startedSystemTimeMs: Long, continuation: Promise[Duration])

  private[this] var currentPing: Option[PingState] = None

  // Must be called from within the sessionExecutor
  def ping(p: Promise[Duration]): Unit = currentPing match {
    case Some(_) =>
      p.tryFailure(new IllegalStateException("Ping already in progress"))
      ()

    case None =>
      val time = System.currentTimeMillis
      currentPing = Some(PingState(time, p))
      val data = new Array[Byte](8)
      ByteBuffer.wrap(data).putLong(time)
      val pingFrame = session.http2Encoder.pingFrame(data)
      // TODO: we can do a lot of cool things with pings by managing when they are written
      session.writeController.write(pingFrame)
  }

  def pingAckReceived(data: Array[Byte]): Unit = {
    currentPing match {
      case None => // nop
      case Some(PingState(sent, continuation)) =>
        currentPing = None

        if (ByteBuffer.wrap(data).getLong != sent) { // data guaranteed to be 8 bytes
          continuation.tryFailure(new Exception("Received ping response with unknown data."))
        } else {
          val duration = Duration.fromNanos((System.currentTimeMillis - sent) * 1000000)
          continuation.trySuccess(duration)
        }
    }
  }
}
