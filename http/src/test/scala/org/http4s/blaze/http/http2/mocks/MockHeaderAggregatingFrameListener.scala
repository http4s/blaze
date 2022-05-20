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

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2._

private[http2] class MockHeaderAggregatingFrameListener
    extends HeaderAggregatingFrameListener(
      Http2Settings.default,
      new HeaderDecoder(20 * 1024, true, 4096)) {
  override def onCompletePushPromiseFrame(
      streamId: Int,
      promisedId: Int,
      headers: Headers): Result = ???
  override def onCompleteHeadersFrame(
      streamId: Int,
      priority: Priority,
      end_stream: Boolean,
      headers: Headers): Result = ???
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = ???
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = ???
  override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = ???

  // For handling unknown stream frames
  override def onRstStreamFrame(streamId: Int, code: Long): Result = ???
  override def onDataFrame(
      streamId: Int,
      isLast: Boolean,
      data: ByteBuffer,
      flowSize: Int): Result = ???
  override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result = ???
}
