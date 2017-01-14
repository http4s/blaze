package org.http4s.blaze.http

import java.nio.ByteBuffer
import scala.concurrent.Future

/** Output pipe for writing http responses */
abstract class BodyWriter private[http] {

  /** Type of value returned upon closing of the `BodyWriter`
    *
    * This type is used to enforce that the writer is closed when writing a
    * HTTP response using the server.
    */
  type Finished

  /** Write a message to the pipeline
    *
    * Write an entire message will be written to the channel. This buffer might not be written
    * to the wire and instead buffered. Buffers can be manually flushed with the `flush` method
    * or by closing the [[BodyWriter]].
    *
    * @param buffer `ByteBuffer` to write to the channel
    * @return a `Future[Unit]` which resolves upon completion. Errors are handled through the `Future`.
    */
  def write(buffer: ByteBuffer): Future[Unit]

  /** Flush any bytes to the pipeline
    *
    * This may be a NOOP depending on the nature of the[[BodyWriter]].
    *
    * @return a `Future[Unit]` that resolves when any buffers have been flushed.
    */
  def flush(): Future[Unit]

  /** Close the writer and flush any buffers
    *
    * @return a `Future[Finished]` which will resolve once the close process has completed.
    */
  def close(): Future[Finished]
}
