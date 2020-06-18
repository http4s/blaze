/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
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
}
