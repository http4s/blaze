package org.http4s.blaze

package object util {
  /** Constructs an assertion error with a reference back to our issue tracker. Use only with head hung low. */
  private[blaze] def bug(message: String): AssertionError =
    new AssertionError(s"This is a bug. Please report to https://github.com/http4s/blaze/issues: ${message}")
}
