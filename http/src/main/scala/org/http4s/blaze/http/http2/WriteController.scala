package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.log4s.getLogger

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/** Gracefully coordinate writes
  *
  * The `WriteController`s job is to direct outbound data in both a fair, efficient, and
  * thread safe manner. All calls to the `WriteControler` are expected to come from within
  * the session executor.
  *
  * @param highWaterMark number of bytes that will trigger a flush.
  */
private abstract class WriteController(highWaterMark: Int) extends WriteListener {

  final protected val logger = getLogger

  private[this] var inWriteCycle = false
  private[this] var pendingWrites = new ArrayBuffer[ByteBuffer](4)
  private[this] var pendingWriteBytes: Int = 0

  // TODO: if we implement priorities, we should prioritize writes as well.
  private[this] val interestedStreams = new java.util.ArrayDeque[WriteInterest]

  /** Write the outbound data to the pipeline */
  protected def writeToWire(data: Seq[ByteBuffer]): Unit

  /** See if this `WriteController` is waiting for data to flush */
  final def awaitingWriteFlush: Boolean = inWriteCycle

  private[this] def pendingInterests: Boolean = !pendingWrites.isEmpty || !interestedStreams.isEmpty

  /** Called when the previous write has completed successfully and the pipeline is read to write again */
  def writeSuccessful(): Unit = {
    assert(inWriteCycle)
    if (pendingInterests) doWrite()
    else {
      inWriteCycle = false
    }
  }

  /** Queue data for the wire
    *
    * The data may be stored in a buffer if a write is currently in progress.
    */
  final def writeOutboundData(data: Seq[ByteBuffer]): Unit = {
    data.foreach(accBuffer)
    maybeWrite()
  }

  /** Queue data for the wire
    *
    * The data may be stored in a buffer if a write is currently in progress.
    */
  final def writeOutboundData(data: ByteBuffer): Unit = {
    accBuffer(data)
    maybeWrite()
  }

  /** Register a listener to be invoked when the pipeline is ready to perform write operations */
  final def registerWriteInterest(stream: WriteInterest): Unit = {
    interestedStreams.add(stream)
    maybeWrite()
  }

  private[this] def accBuffer(data: ByteBuffer): Unit = {
    if (data.hasRemaining) {
      pendingWrites += data
      pendingWriteBytes += data.remaining()
    }
  }

  private[this] def maybeWrite(): Unit = {
    if (!inWriteCycle) doWrite()
  }

  // The meat and potatoes
  private[this] def doWrite(): Unit = {
    // `inWriteCycle` needs to be set to `true` here so that when streams act
    // on their write interests they don't induce another call to `doWrite`.
    inWriteCycle = true

    // Accumulate bytes until we run out of interests or have exceeded the high-water mark
    while(!interestedStreams.isEmpty && pendingWriteBytes < highWaterMark) {
      try {
        val data = interestedStreams.poll().performStreamWrite()
        writeOutboundData(data)
      } catch { case NonFatal(t) =>
        logger.error(t)(s"Unhandled exception performing stream write operation")
      }
    }

    if (pendingWriteBytes > 0) {
      // We have some data waiting to go out. Take it and make a new accumulation buffer
      val out = pendingWrites

      pendingWrites = new ArrayBuffer[ByteBuffer](out.length + 4)
      pendingWriteBytes = 0

      writeToWire(out)
    } else {
      // Didn't write anything, so the cycle is finished
      inWriteCycle = false
    }
  }
}
