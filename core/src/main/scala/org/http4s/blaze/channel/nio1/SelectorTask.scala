package org.http4s.blaze.channel.nio1

import java.nio.ByteBuffer

private trait SelectorTask {
  def run(scratch: ByteBuffer): Unit
}

private object SelectorTask {
  def apply(runnable: Runnable): SelectorTask = new SelectorTask {
    override def run(scratch: ByteBuffer): Unit = runnable.run()
  }
}
