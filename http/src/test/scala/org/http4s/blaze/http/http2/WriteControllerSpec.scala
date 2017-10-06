package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.specs2.mutable.Specification

import scala.collection.mutable.ArrayBuffer

class WriteControllerSpec extends Specification {

  private class TestWriteController(highWaterMark: Int) extends WriteController(highWaterMark) {

    val pendingWrites = new ArrayBuffer[ByteBuffer]()

    override protected def writeToWire(data: Seq[ByteBuffer]): Unit = {

    }
  }

  "WriteController" should {
    ok
  }

}
