package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{WriteController, WriteInterest}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

private[http2] class MockWriteController extends WriteController {
  var closeCalled = false
  val observedInterests = new ListBuffer[WriteInterest]
  val observedWrites = mutable.Queue.empty[ByteBuffer]

  override def registerWriteInterest(interest: WriteInterest): Boolean = {
    observedInterests += interest
    true
  }

  /** Drain any existing messages with the future resolving on completion */
  override def close(): Future[Unit] = {
    closeCalled = true
    Future.successful(())
  }

  /** Queue multiple buffers for writing */
  override def write(data: Seq[ByteBuffer]): Boolean = {
    observedWrites ++= data
    true
  }

  /** Queue a buffer for writing */
  final override def write(data: ByteBuffer): Boolean =
    write(data::Nil)
}
