/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.pipeline.stages

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.{Command, HeadStage}

import scala.concurrent.{Future, Promise}

import scala.collection.mutable

abstract class DelayHead[I](delay: Duration) extends HeadStage[I] {
  import DelayHead.highresTimer

  def next(): I

  def name: String = "DelayHead"

  override protected def doClosePipeline(cause: Option[Throwable]): Unit = ()

  private val awaitingPromises = new mutable.HashSet[Promise[_]]()

  private def rememberPromise(p: Promise[_]): Unit = {
    awaitingPromises.synchronized {
      awaitingPromises += p
    }
    ()
  }

  private def unqueue(p: Promise[_]): Unit = {
    awaitingPromises.synchronized {
      awaitingPromises.remove(p)
    }
    ()
  }

  override def readRequest(size: Int): Future[I] = {
    val p = Promise[I]

    rememberPromise(p)

    highresTimer.schedule(new Runnable {
      def run(): Unit = {
        p.trySuccess(next())
        unqueue(p)
      }
    }, delay.toNanos, TimeUnit.NANOSECONDS)
    p.future
  }

  override def writeRequest(data: I): Future[Unit] = {
    val p = Promise[Unit]
    highresTimer.schedule(new Runnable {
      def run(): Unit = {
        p.trySuccess(())
        unqueue(p)
      }
    }, delay.toNanos, TimeUnit.NANOSECONDS)

    rememberPromise(p)
    p.future
  }

  override protected def stageShutdown(): Unit = {
    awaitingPromises.synchronized {
      awaitingPromises.foreach(p => p.tryFailure(Command.EOF))
    }
    super.stageShutdown()
  }
}

private object DelayHead {
  private val highresTimer = new ScheduledThreadPoolExecutor(1)
}
