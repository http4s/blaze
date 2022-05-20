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

package org.http4s.blaze

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.LeafBuilder

import scala.concurrent.Future

package object channel {
  type SocketPipelineBuilder = SocketConnection => Future[LeafBuilder[ByteBuffer]]

  /** Default number of threads used to make a new
    * [[org.http4s.blaze.channel.nio1.SelectorLoopPool]] if not specified
    */
  val DefaultPoolSize: Int =
    math.max(4, Runtime.getRuntime.availableProcessors() + 1)

  /** Default max number of connections that can be active at any time. A negative number means that
    * there is no max.
    */
  val DefaultMaxConnections: Int = 512
}
