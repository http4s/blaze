package org.http4s.blaze.util

object Cancellable {
  val noopCancel = new Cancellable {
    def cancel(): Unit = ()
  }
}

trait Cancellable {
  def cancel(): Unit
}