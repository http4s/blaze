package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

class AbstractBodyReaderSpec extends Specification {

  // We expect all futures to be complete immediately
  private def now[T](f: Future[T]): T = f.value match {
    case Some(Success(t)) => t
    case Some(Failure(t)) => throw t
    case None => sys.error("Expected the future to be finished")
  }

  private def bytes(size: Int): ByteBuffer = {
    val b = BufferTools.allocate(size)
    for (i <- 0 until size) {
      b.put(i.toByte)
    }
    b.flip()
    b
  }

  private class TestBodyReader(length: Long, data: Seq[StreamFrame]) extends AbstractBodyReader(1, length) {
    var failedReason: Option[Throwable] = None
    val reads = new mutable.Queue[StreamFrame]
    reads ++= data

    override protected def channelRead(): Future[StreamFrame] =
      Future.successful(reads.dequeue())

    override protected def failed(ex: Throwable): Unit = {
      assert(failedReason == None)
      failedReason = Some(ex)
    }
  }

  "AbstractBodyReaderSpec" >> {
    "can read data" >> {
      val reader = new TestBodyReader(-1, Seq(DataFrame(false, bytes(1)), DataFrame(true, bytes(2))))
      now(reader()) must_== bytes(1)
      reader.isExhausted must beFalse
      now(reader()) must_== bytes(2)
      reader.isExhausted must beTrue
    }

    "results in an error if more bytes are available than the content-length header allows" >> {
      val reader = new TestBodyReader(2, Seq(DataFrame(false, bytes(1)), DataFrame(true, bytes(2))))
      now(reader()) must_== bytes(1)
      reader.isExhausted must beFalse
      reader().value must beLike {
        case Some(Failure(ex: Http2StreamException)) => ex.code must_== Http2Exception.PROTOCOL_ERROR.code
      }
      reader.isExhausted must beTrue
    }

    "discard causes subsequent reads to be empty" >> {
      val reader = new TestBodyReader(-1, Seq(DataFrame(false, bytes(1)), DataFrame(true, bytes(2))))
      now(reader()) must_== bytes(1)
      reader.isExhausted must beFalse
      reader.discard()
      reader.isExhausted must beTrue
      now(reader()) must_== BufferTools.emptyBuffer
    }

    "unexpected header frames result in a protocol error" >> {
      val data = Seq(HeadersFrame(Priority.NoPriority, false, Seq.empty))
      val reader = new TestBodyReader(-1, data)
      reader().value must beLike {
        case Some(Failure(ex: Http2StreamException)) => ex.code must_== Http2Exception.PROTOCOL_ERROR.code
      }
      reader.isExhausted must beTrue
    }
  }
}
