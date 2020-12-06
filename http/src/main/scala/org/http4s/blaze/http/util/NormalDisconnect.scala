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

package org.http4s.blaze.http.util

import org.http4s.blaze.pipeline.Command

import java.util.concurrent.TimeoutException

import scala.util.{Failure, Try}

/** Helper to collect errors that we don't care much about */
private[http] object NormalDisconnect {
  def unapply(t: Try[Any]): Option[Exception] =
    t match {
      case Failure(t) => unapply(t)
      case _ => None
    }

  def unapply(t: Throwable): Option[Exception] =
    t match {
      case Command.EOF => Some(Command.EOF)
      case t: TimeoutException => Some(t)
      case _ => None
    }
}
