/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.util

object Cancelable {

  /** Cancelable which does nothing */
  val NoopCancel = new Cancelable {
    def cancel(): Unit = ()
  }
}

/** Type that can be canceled. */
trait Cancelable {

  /** Attempt to cancel this `Cancelable`.
    *
    * Cancellation is not guaranteed.
    */
  def cancel(): Unit
}
