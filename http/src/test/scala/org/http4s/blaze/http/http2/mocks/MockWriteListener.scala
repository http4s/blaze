package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{WriteInterest, WriteListener}

import scala.collection.mutable.ListBuffer

private[http2] class MockWriteListener extends WriteListener {
  val observedInterests = new ListBuffer[WriteInterest]

  override def registerWriteInterest(interest: WriteInterest): Unit = {
    observedInterests += interest
    ()
  }
}
