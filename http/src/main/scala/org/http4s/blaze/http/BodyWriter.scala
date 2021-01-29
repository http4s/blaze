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

package org.http4s.blaze.http

import java.nio.ByteBuffer
import scala.concurrent.Future

/** Output pipe for writing http responses
  *
  * This is, more or less, an asynchronous `OutputStream`
  */
trait BodyWriter {

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
    * This may be a no-op depending on the nature of the[[BodyWriter]].
    *
    * @return a `Future[Unit]` that resolves when any buffers have been flushed.
    */
  def flush(): Future[Unit]

  /** Close the writer and flush any buffers
    *
    * If the reason is `Some`, this means that the message was not completely written
    * due to the provided error.
    *
    * @return a `Future[Finished]` which will resolve once the close process has completed.
    */
  def close(reason: Option[Throwable]): Future[Finished]
}
