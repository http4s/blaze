/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.pipeline.HeadStage
import scala.collection.mutable
import scala.concurrent.{Future, Promise}

private[http2] class MockHeadStage[T] extends HeadStage[T] {
  override def name: String = "Head"

  val reads = new mutable.Queue[Promise[T]]()
  val writes = new mutable.Queue[(T, Promise[Unit])]()

  var disconnected: Boolean = false
  var error: Option[Throwable] = None

  override def readRequest(size: Int): Future[T] = {
    val p = Promise[T]()
    reads += p
    p.future
  }

  def consumeOutboundData(): Seq[T] = {
    // We need to take all the writes and then clear since completing the
    // promises might trigger more writes
    val writePairs = writes.toList
    writes.clear()

    writePairs.map { case (b, p) =>
      p.success(())
      b
    }
  }

  override def writeRequest(data: T): Future[Unit] = {
    val p = Promise[Unit]()
    writes += data -> p
    p.future
  }

  override protected def doClosePipeline(cause: Option[Throwable]): Unit = {
    disconnected = true
    error = cause
  }
}
