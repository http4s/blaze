/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.TailStage

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.concurrent.Promise

class MapTail[A](f: A => A) extends TailStage[A] {
  override def name = "MapTail"

  def startLoop(): Future[Unit] = {
    val p = Promise[Unit]
    innerLoop(p)
    p.future
  }

  private def innerLoop(p: Promise[Unit]): Unit =
    channelRead(-1, 10.seconds)
      .flatMap(a => channelWrite(f(a)))
      .onComplete {
        case Success(_) => innerLoop(p)
        case Failure(EOF) => p.success(())
        case e => p.complete(e)
      }
}

class EchoTail[A] extends MapTail[A](identity)
