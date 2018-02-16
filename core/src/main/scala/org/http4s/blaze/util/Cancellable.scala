package org.http4s.blaze.util

object Cancellable {
  /** Cancellable which does nothing */
  val NoopCancel = new Cancellable {
    def cancel(): Unit = ()
  }
}

/** Type that can be canceled. */
trait Cancellable {

  /** Attempt to cancel this `Cancellable`.
    *
    * Cancellation is not guaranteed.
    */
  def cancel(): Unit
}
