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

package org.http4s.blaze

import org.http4s.blaze.pipeline.Command.EOF
import scala.concurrent.Future

package object util {

  /** Constructs an assertion error with a reference back to our issue tracker. Use only with head
    * hung low.
    */
  private[blaze] def bug(message: String): AssertionError =
    new AssertionError(
      s"This is a bug. Please report to https://github.com/http4s/blaze/issues: $message")

  // Can replace with `Future.unit` when we drop 2.11 support
  private[blaze] val FutureUnit: Future[Unit] = Future.successful(())

  private[blaze] val FutureEOF: Future[Nothing] = Future.failed(EOF)
}
