package org.http4s.blaze

import org.http4s.blaze.pipeline.Command.EOF
import scala.concurrent.Future

package object util {

  /** Constructs an assertion error with a reference back to our issue tracker. Use only with head hung low. */
  private[blaze] def bug(message: String): AssertionError =
    new AssertionError(
      s"This is a bug. Please report to https://github.com/http4s/blaze/issues: ${message}")

  @deprecated("Renamed to `Cancelable`", "0.19.0-M6")
  type Cancellable = Cancelable
  @deprecated("Renamed to `Cancelable`", "0.19.0-M6")
  val Cancellable = Cancelable

  // Can replace with `Future.unit` when we drop 2.11 support
  private[blaze] val FutureUnit: Future[Unit] = Future.successful(())

  private[blaze] val FutureEOF: Future[Nothing] = Future.failed(EOF)
}
