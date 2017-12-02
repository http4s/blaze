package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import scala.concurrent.Future

/** Generic interface used by HTTP2 types to write data */
private trait WriteController {

  /** Register a [[WriteInterest]] with this listener to be invoked later once it is
    * possible to write data to the outbound channel.
    *
    * @param interest the `WriteListener` with an interest in performing a write operation.
    */
  def registerWriteInterest(interest: WriteInterest): Unit

  /** Drain any existing messages with the future resolving on completion */
  def close(): Future[Unit]

  /** Queue multiple buffers for writing */
  def write(data: Seq[ByteBuffer]): Unit

  /** Queue a buffer for writing */
  def write(data: ByteBuffer): Unit
}