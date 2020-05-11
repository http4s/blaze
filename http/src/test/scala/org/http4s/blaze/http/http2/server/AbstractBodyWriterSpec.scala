/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, Priority, StreamFrame}
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.{BufferTools, FutureUnit}
import org.specs2.mutable.Specification
import scala.concurrent.Future
import scala.util.{Failure, Success}

class AbstractBodyWriterSpec extends Specification {
  private val hs = Seq("one" -> "two")

  private def hsFrame(eos: Boolean): HeadersFrame = HeadersFrame(Priority.NoPriority, eos, hs)

  private def data(size: Int): ByteBuffer = BufferTools.allocate(size)

  private def dataFrame(eos: Boolean, size: Int): DataFrame = DataFrame(eos, data(size))

  private def checkSuccess(t: Future[Unit]): Boolean =
    t.value match {
      case Some(Success(())) => true
      case other => sys.error(s"Unexpected value: $other")
    }

  private def checkIOException(t: Future[Unit]): Boolean =
    t.value match {
      case Some(Failure(Command.EOF)) => true // nop
      case other => sys.error(s"Unexpected value: $other")
    }

  private class WriterImpl extends AbstractBodyWriter(hs) {
    var flushed: Option[Seq[StreamFrame]] = None

    var failedReason: Option[Throwable] = None

    override protected def fail(cause: Throwable): Unit = {
      assert(failedReason.isEmpty)
      failedReason = Some(cause)
    }

    override protected def flushMessage(msg: StreamFrame): Future[Unit] = flushMessage(msg :: Nil)

    override protected def flushMessage(msg: Seq[StreamFrame]): Future[Unit] = {
      assert(flushed.isEmpty)
      flushed = Some(msg)
      FutureUnit
    }
  }

  "AbstractBodyWriter" >> {
    "flush" >> {
      "flushes headers if they haven't been written" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must_== Some(Seq(hsFrame(false)))
      }

      "flushes nothing if the headers are already gone" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must_== Some(Seq(hsFrame(false)))
        writer.flushed = None

        // try again
        checkSuccess(writer.flush())
        writer.flushed must beNone
      }

      "flush results in failure of already closed" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close(None))
        checkIOException(writer.flush())
      }
    }

    "write" >> {
      "flushes headers and data if they haven't been written" >> {
        val writer = new WriterImpl
        checkSuccess(writer.write(data(1)))
        writer.flushed must_== Some(Seq(hsFrame(false), dataFrame(false, 1)))
      }

      "flushes data if the headers are already gone" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must beSome
        writer.flushed = None

        checkSuccess(writer.write(data(1)))
        writer.flushed must_== Some(Seq(dataFrame(false, 1)))
      }

      "flush results in failure of already closed" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close(None))
        writer.flushed must beSome
        writer.flushed = None

        checkIOException(writer.write(data(1)))
      }
    }

    "close" >> {
      "flushes headers if they haven't been written" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close(None))
        writer.flushed must_== Some(Seq(hsFrame(true)))
      }

      "flushes empty data if the headers are already gone" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must_== Some(Seq(hsFrame(false)))
        writer.flushed = None

        checkSuccess(writer.close(None))
        writer.flushed must_== Some(Seq(dataFrame(true, 0)))
      }

      "results in failure of already closed" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close(None))
        writer.flushed must_== Some(Seq(hsFrame(true)))
        writer.flushed = None

        checkIOException(writer.close(None))
      }

      "with cause doesn't flush" >> {
        val writer = new WriterImpl
        val ex = new Exception("boom")
        checkSuccess(writer.close(Some(ex)))
        writer.flushed must beNone
        writer.failedReason must beSome(ex)
      }
    }
  }
}
