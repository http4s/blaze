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
import java.util

import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.FutureUnit
import org.log4s.getLogger

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** Gracefully coordinate writes
  *
  * The `WriteController`'s job is to direct outbound data in a fair, efficient, and thread safe
  * manner. All calls to the `WriteController` are expected to come from within the session
  * executor.
  *
  * @param highWaterMark
  *   number of bytes that will trigger a flush.
  */
private final class WriteControllerImpl(
    session: SessionCore,
    highWaterMark: Int,
    tailStage: TailStage[ByteBuffer]
) extends WriteController {
  import WriteControllerImpl._

  private[this] val logger = getLogger
  // TODO: if we implement priorities, we should prioritize writes as well.
  private[this] val interestedStreams = new util.ArrayDeque[WriteInterest]
  private[this] val pendingWrites = new util.ArrayDeque[Seq[ByteBuffer]]

  private[this] var state: State = Idle

  def close(): Future[Unit] =
    state match {
      case Idle =>
        state = Closed
        FutureUnit

      case Flushing =>
        val p = Promise[Unit]()
        state = Closing(p)
        p.future

      case Closing(p) =>
        p.future

      case Closed =>
        FutureUnit
    }

  private[this] def pendingInterests: Boolean =
    !pendingWrites.isEmpty || !interestedStreams.isEmpty

  def write(data: Seq[ByteBuffer]): Boolean =
    state match {
      case Idle | Flushing | Closing(_) =>
        pendingWrites.addLast(data)
        maybeWrite()
        true

      case Closed =>
        false
    }

  def write(data: ByteBuffer): Boolean = write(data :: Nil)

  def registerWriteInterest(interest: WriteInterest): Boolean =
    state match {
      case Closed => false
      case _ =>
        interestedStreams.add(interest)
        maybeWrite()
        true
    }

  private[this] def maybeWrite(): Unit =
    if (state == Idle) {
      state = Flushing
      doWrite()
    }

  private[this] def addDirectWrites(dest: ArrayBuffer[ByteBuffer]): Int = {
    var written = 0
    while (!pendingWrites.isEmpty)
      written += addBuffs(dest, pendingWrites.poll())
    written
  }

  private[this] def addBuffs(
      dest: ArrayBuffer[ByteBuffer],
      data: collection.Seq[ByteBuffer]): Int = {
    var written = 0
    data.foreach { buf =>
      val rem = buf.remaining
      if (0 < rem) {
        written += rem
        dest += buf
      }
    }
    written
  }

  // The meat and potatoes
  private[this] def doWrite(): Unit = {
    val toWrite = new ArrayBuffer[ByteBuffer]()
    var bytesToWrite = addDirectWrites(toWrite)

    // Accumulate bytes until we run out of interests or have exceeded the high-water mark
    while (!interestedStreams.isEmpty && bytesToWrite < highWaterMark)
      try {
        val data = interestedStreams.poll().performStreamWrite()
        bytesToWrite += addBuffs(toWrite, data)
      } catch {
        case NonFatal(t) =>
          logger.error(t)(s"Unhandled exception performing stream write operation")
      }

    logger.debug(s"Flushing $bytesToWrite to the wire")

    tailStage
      .channelWrite(toWrite)
      .onComplete {
        case Success(_) =>
          state match {
            case Idle =>
              throw new IllegalStateException("Write finished to find Idle state")

            case Flushing if pendingInterests =>
              doWrite()

            case Flushing =>
              state = Idle

            case Closing(_) if pendingInterests =>
              doWrite()

            case Closing(p) =>
              state = Closed
              p.success(())

            case Closed =>
              throw new IllegalStateException("Shouldn't get here")
          }

        case Failure(t) =>
          session.invokeShutdownWithError(Some(t), "WriteController.doWrite")
      }(session.serialExecutor)
  }
}

private object WriteControllerImpl {
  private sealed trait State
  private case object Idle extends State
  private case object Flushing extends State
  private case class Closing(p: Promise[Unit]) extends State
  private case object Closed extends State
}
