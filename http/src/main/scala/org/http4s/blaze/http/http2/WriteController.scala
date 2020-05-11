/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import scala.concurrent.Future

// TODO: writing should be in terms of something that can be turned into a buffer
// but can still be examined since it is currently impossible to introspect even
// the boundaries of where one message starts and another begins.
// This would allow us to prioritize messages (like PING responses).
/** Generic interface used by HTTP2 types to write data */
private trait WriteController {

  /** Register a [[WriteInterest]] with this listener to be invoked later once it is
    * possible to write data to the outbound channel.
    *
    * @param interest the `WriteListener` with an interest in performing a write operation.
    * @return true if registration successful, false otherwise
    */
  def registerWriteInterest(interest: WriteInterest): Boolean

  /** Drain any existing messages with the future resolving on completion */
  def close(): Future[Unit]

  /** Queue multiple buffers for writing
    *
    * @return true if the data was scheduled for writing, false otherwise.
    */
  def write(data: Seq[ByteBuffer]): Boolean

  /** Queue a buffer for writing
    *
    * @return true if the data was scheduled for writing, false otherwise.
    */
  def write(data: ByteBuffer): Boolean
}
