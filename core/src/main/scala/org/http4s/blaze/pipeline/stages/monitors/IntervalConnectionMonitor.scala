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

package org.http4s.blaze.pipeline.stages.monitors

import scala.concurrent.duration.Duration

class IntervalConnectionMonitor(val interval: Duration) extends ConnectionMonitor {
  require(
    interval.isFinite && interval.toNanos > 1,
    "Duration must be Finite and greater than 1 ns")

  private val alpha = 1.0 / (interval.toNanos + 1).toDouble

  private val inbound = new MeanTracker
  private val outbound = new MeanTracker
  private val conns = new ConnTracker

  // Tracks the mean and total of something
  private class MeanTracker {
    private var currentMean = 0.0
    private var lastupdate = System.nanoTime()
    private var total = 0L

    def getMean(): Double = currentMean * 1e9 // bytes/sec
    def getTotal(): Long = total

    def update(c: Long): Unit =
      this.synchronized {
        total += c
        val currentTime = System.nanoTime()
        val ticks = currentTime - lastupdate
        lastupdate = currentTime
        currentMean = currentMean * math
          .pow(1.0 - alpha, ticks.toDouble) + c.toDouble * alpha
      }
  }

  // Also tracks the number of live connections
  private class ConnTracker extends MeanTracker {
    private var currentLive = 0L

    def getLive(): Long = currentLive

    override def update(c: Long): Unit =
      this.synchronized {
        currentLive += c
        super.update(c)
      }

    def closed(): Unit =
      this.synchronized {
        currentLive -= 1
      }
  }

  case class Stats(
      imean: Double,
      itotal: Long,
      omean: Double,
      ototal: Long,
      connmean: Double,
      conntotal: Long,
      connlive: Long) {
    override def toString: String = {
      val mb = (1024 * 1024).toDouble
      val (f1, unit1) =
        if (math.max(imean, omean) > mb) (mb, "MB") else (1024.0, "kB")
      val (f2, unit2) =
        if (math.max(itotal, ototal) > 1024.0 * mb) (1024.0 * mb, "GB")
        else (mb, "MB")

      s"""                 Mean (%s/s)     Total (%s)
         |Inbound bytes    %11.3f     %10.3f
         |Outbound bytes   %11.3f     %10.3f
         |
         |Connections      %-10.6f      %10d
         |
         |Live Connections: %d
       """.stripMargin.format(
        unit1,
        unit2,
        imean / f1,
        itotal / f2,
        omean / f1,
        ototal / f2,
        connmean,
        conntotal,
        connlive)
    }
  }

  def getStats(): Stats =
    inbound.synchronized(outbound.synchronized(conns.synchronized {
      Stats(
        inbound.getMean(),
        inbound.getTotal(),
        outbound.getMean(),
        outbound.getTotal(),
        conns.getMean(),
        conns.getTotal(),
        conns.getLive())
    }))

  override protected def connectionAccepted(): Unit = conns.update(1)
  override protected def bytesInbound(n: Long): Unit = inbound.update(n)
  override protected def connectionClosed(): Unit = conns.closed()
  override protected def bytesOutBound(n: Long): Unit = outbound.update(n)
}
