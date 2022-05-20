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

package org.http4s.blaze.channel.nio2

import org.http4s.blaze.channel.ChannelHead
import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.util.{BufferTools, FutureUnit}

import scala.concurrent.{Future, Promise}

import java.nio.channels._
import java.nio.ByteBuffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.lang.{Long => JLong}

private[nio2] final class ByteBufferHead(channel: AsynchronousSocketChannel, bufferSize: Int)
    extends ChannelHead {
  def name: String = "ByteBufferHeadStage"

  @volatile
  private[this] var closeReason: Option[Throwable] = None
  private[this] val scratchBuffer = ByteBuffer.allocateDirect(bufferSize)

  override def writeRequest(data: ByteBuffer): Future[Unit] =
    writeRequest(data :: Nil)

  override def writeRequest(data: collection.Seq[ByteBuffer]): Future[Unit] =
    closeReason match {
      case Some(cause) => Future.failed(cause)
      case None if data.isEmpty => FutureUnit
      case None =>
        val p = Promise[Unit]()
        val srcs = data.toArray

        def go(index: Int): Unit =
          channel.write[Null](
            srcs,
            index,
            srcs.length - index,
            -1L,
            TimeUnit.MILLISECONDS,
            null: Null,
            new CompletionHandler[JLong, Null] {
              def failed(exc: Throwable, attachment: Null): Unit = {
                val e = checkError(exc)
                p.tryFailure(e)
                ()
              }

              def completed(result: JLong, attachment: Null): Unit =
                if (!BufferTools.checkEmpty(srcs)) go(BufferTools.dropEmpty(srcs))
                else {
                  p.success(())
                  ()
                }
            }
          )

        go(0)
        p.future
    }

  def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]()
    scratchBuffer.clear()

    if (size >= 0 && size < bufferSize)
      scratchBuffer.limit(size)

    channel.read(
      scratchBuffer,
      null: Null,
      new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null): Unit = {
          val e = checkError(exc)
          p.failure(e)
          ()
        }

        def completed(i: Integer, attachment: Null): Unit =
          i.intValue match {
            case 0 =>
              p.success(BufferTools.emptyBuffer)
              ()

            case i if i < 0 =>
              p.failure(EOF)
              ()

            case _ =>
              scratchBuffer.flip()
              p.success(BufferTools.copyBuffer(scratchBuffer))
              ()
          }
      }
    )
    p.future
  }

  override protected def checkError(e: Throwable): Throwable =
    e match {
      case e: ShutdownChannelGroupException =>
        logger.debug(e)("Channel Group was shutdown")
        EOF

      case t => super.checkError(t)
    }

  override protected def doClosePipeline(cause: Option[Throwable]): Unit = {
    val needsClose = synchronized {
      val reason = closeReason
      reason match {
        case None | Some(EOF) =>
          closeReason = Some(cause.getOrElse(EOF))
        case _ => // nop
      }
      reason.isEmpty
    }

    cause match {
      case Some(t) => logger.error(t)("NIO2 channel closed with error")
      case None => logger.debug(s"doClosePipeline(None)")
    }

    if (needsClose)
      try channel.close()
      catch { case _: IOException => /* Don't care */ }
  }
}
