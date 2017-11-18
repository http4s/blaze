package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.pipeline.TailStage
import org.log4s.getLogger

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** Gracefully coordinate writes
  *
  * The `WriteController`s job is to direct outbound data in both a fair, efficient, and
  * thread safe manner. All calls to the `WriteController` are expected to come from within
  * the session executor.
  *
  * @param highWaterMark number of bytes that will trigger a flush.
  */
private class WriteController(
  session: SessionCore,
  highWaterMark: Int,
  tailStage: TailStage[ByteBuffer]
) extends WriteListener {
  import WriteController._

  private[this] val logger = getLogger
  // TODO: if we implement priorities, we should prioritize writes as well.
  private[this] val interestedStreams = new java.util.ArrayDeque[WriteInterest]
  private[this] val pendingWrites = new util.ArrayDeque[Seq[ByteBuffer]]

  private[this] var state: State = Idle

  def close(): Future[Unit] = state match {
    case Idle | Flushing =>
      val p = Promise[Unit]
      state = Closing(p)
      p.future

    case Closing(p) =>
      p.future

    case Closed =>
      Future.successful(())
  }

  private[this] def pendingInterests: Boolean = !pendingWrites.isEmpty || !interestedStreams.isEmpty

  /** Queue data for the wire
    *
    * The data may be stored in a buffer if a write is currently in progress.
    */
  final def write(data: Seq[ByteBuffer]): Unit = state match {
    case Idle | Flushing | Closing(_) =>
      pendingWrites.push(data)
      maybeWrite()

    case Closed => () // TODO: what here?
  }

  /** Queue data for the wire
    *
    * The data may be stored in a buffer if a write is currently in progress.
    */
  final def write(data: ByteBuffer): Unit = write(data::Nil)

  /** Register a listener to be invoked when the pipeline is ready to perform write operations */
  final def registerWriteInterest(stream: WriteInterest): Unit = {
    interestedStreams.add(stream)
    maybeWrite()
  }

  private[this] def maybeWrite(): Unit = {
    if (state == Idle) {
      state = Flushing
      doWrite()
    }
  }

  private[this] def addDirectWrites(dest: ArrayBuffer[ByteBuffer]): Int = {
    var written = 0
    while (!pendingWrites.isEmpty) {
      written += addBuffs(dest, pendingWrites.poll())
    }
    written
  }

  private[this] def addBuffs(dest: ArrayBuffer[ByteBuffer], data: Seq[ByteBuffer]): Int = {
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
    while(!interestedStreams.isEmpty && bytesToWrite < highWaterMark) {
      try {
        val data = interestedStreams.poll().performStreamWrite()
        bytesToWrite += addBuffs(toWrite, data)
      } catch { case NonFatal(t) =>
        logger.error(t)(s"Unhandled exception performing stream write operation")
      }
    }

    logger.debug(s"Flushing $bytesToWrite to the wire")

    tailStage.channelWrite(toWrite).onComplete {
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
            p.trySuccess(())

          case Closed =>
            () // shouldn't get here
        }

      case Failure(t) =>
        session.invokeShutdownWithError(Some(t), "WriteController.doWrite")
    }(session.serialExecutor)
  }
}

object WriteController {
  private sealed trait State
  private case object Idle extends State
  private case object Flushing extends State
  private case class Closing(p: Promise[Unit]) extends State
  private case object Closed extends State
}
