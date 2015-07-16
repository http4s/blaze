package org.http4s.blaze.util

/**
 * Created on 7/16/15.
 */
object Cancellable {
  private[util] val noopCancel = new Cancellable {
    def cancel(): Unit = ()
  }
}

trait Cancellable {
  def cancel(): Unit
}