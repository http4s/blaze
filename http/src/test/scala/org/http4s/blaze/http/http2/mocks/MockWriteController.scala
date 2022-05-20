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

package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{WriteController, WriteInterest}
import org.http4s.blaze.util.FutureUnit

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
    FutureUnit
  }

  /** Queue multiple buffers for writing */
  override def write(data: Seq[ByteBuffer]): Boolean = {
    observedWrites ++= data
    true
  }

  /** Queue a buffer for writing */
  final override def write(data: ByteBuffer): Boolean =
    write(data :: Nil)
}
