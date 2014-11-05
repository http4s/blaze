package org.http4s.blaze.util

import scala.concurrent.duration.Duration


object TimingTools {

  def spin(spinTime: Duration)(finished: => Boolean): Unit = {
    val start = System.nanoTime()
    while(!finished && System.nanoTime() - start < spinTime.toNanos) {
      Thread.sleep(1) /* spin up to 5 seconds */
    }
  }

}
