package org.http4s.blaze.util

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.TailStage

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

private[http4s] object StageTools {

  /** Accumulate bytes from a pipeline
    *
    * @param bytes the minimum number of by bytes desired
    * @param stage pipeline stage that you want to pull the bytes from
    * @return a `Future` which contains a `ByteBuffer` with at least `bytes` bytes or a
    *         `Throwable` received by the pipeline.
    */
  def accumulateAtLeast(bytes: Int, stage: TailStage[ByteBuffer]): Future[ByteBuffer] = {
    if (bytes < 0) throw new IllegalArgumentException(s"Cannot read negative bytes: $bytes.")
    else if (bytes == 0) Future.successful(BufferTools.emptyBuffer)
    else {
      def accLoop(bytesToGo: Int, buffers: ArrayBuffer[ByteBuffer], p: Promise[ByteBuffer]): Unit = {
        // presumes that bytesToGo > 0
        stage.channelRead(bytesToGo).onComplete {
          case Success(buffer) =>
            buffers += buffer
            val remaining = bytesToGo - buffer.remaining()
            if (remaining > 0) accLoop(remaining, buffers, p)
            else {  // finished
              // note that remaining is 0 or negative.
              val size = bytes - remaining
              val out = BufferTools.allocate(size)
              buffers.foreach(out.put(_))
              out.flip()
              p.trySuccess(out)
            }

          case f@Failure(_) => p.tryComplete(f)
        }(Execution.trampoline)
      }

      val p = Promise[ByteBuffer]
      accLoop(bytes, new ArrayBuffer[ByteBuffer](8), p)
      p.future
    }
  }
}
