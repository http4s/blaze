package org.http4s.blaze.http.http2.server

import java.io.IOException
import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, Priority, StreamFrame}
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.concurrent.Future
import scala.util.{Failure, Success}

class AbstractBodyWriterSpec extends Specification {

  private val hs = Seq("one" -> "two")

  private def hsFrame(eos: Boolean): HeadersFrame = HeadersFrame(Priority.NoPriority, eos, hs)

  private def data(size: Int): ByteBuffer = BufferTools.allocate(size)

  private def dataFrame(eos: Boolean, size: Int): DataFrame = DataFrame(eos, data(size))

  private def checkSuccess(t: Future[Unit]): Boolean = t.value match {
    case Some(Success(())) => true
    case other => sys.error(s"Unexpected value: $other")
  }

  private def checkIOException(t: Future[Unit]): Boolean = t.value match {
    case Some(Failure(Command.EOF)) => true // nop
    case other => sys.error(s"Unexpected value: $other")
  }

  private class WriterImpl extends AbstractBodyWriter(hs) {

    var flushed: Option[Seq[StreamFrame]] = None

    override protected def flushMessage(msg: StreamFrame): Future[Unit] = flushMessage(msg::Nil)

    override protected def flushMessage(msg: Seq[StreamFrame]): Future[Unit] = {
      assert(flushed.isEmpty)
      flushed = Some(msg)
      Future.successful(())
    }
  }

  "AbstractBodyWriter" >> {
    "flush" >> {
      "Flushes headers if they haven't been written" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must_== Some(Seq(hsFrame(false)))
      }

      "Flushes nothing if the headers are already gone" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must_== Some(Seq(hsFrame(false)))
        writer.flushed = None

        // try again
        checkSuccess(writer.flush())
        writer.flushed must beNone
      }

      "Flush results in failure of already closed" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close())
        checkIOException(writer.flush())
      }
    }

    "write" >> {
      "Flushes headers and data if they haven't been written" >> {
        val writer = new WriterImpl
        checkSuccess(writer.write(data(1)))
        writer.flushed must_== Some(Seq(hsFrame(false), dataFrame(false, 1)))
      }

      "Flushes data if the headers are already gone" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must beSome
        writer.flushed = None

        checkSuccess(writer.write(data(1)))
        writer.flushed must_== Some(Seq(dataFrame(false, 1)))
      }

      "Flush results in failure of already closed" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close())
        writer.flushed must beSome
        writer.flushed = None

        checkIOException(writer.write(data(1)))
      }
    }

    "close" >> {
      "Flushes headers if they haven't been written" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close())
        writer.flushed must_== Some(Seq(hsFrame(true)))
      }

      "Flushes empty data if the headers are already gone" >> {
        val writer = new WriterImpl
        checkSuccess(writer.flush())
        writer.flushed must_== Some(Seq(hsFrame(false)))
        writer.flushed = None

        checkSuccess(writer.close())
        writer.flushed must_== Some(Seq(dataFrame(true, 0)))
      }

      "results in failure of already closed" >> {
        val writer = new WriterImpl
        checkSuccess(writer.close())
        writer.flushed must_== Some(Seq(hsFrame(true)))
        writer.flushed = None

        checkIOException(writer.close())
      }
    }
  }

}
