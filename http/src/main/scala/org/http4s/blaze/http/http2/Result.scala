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

package org.http4s.blaze.http.http2

/** Result type of many of the codec methods */
private sealed trait Result extends Product with Serializable

/** Didn't get enough data to decode a full HTTP/2 frame */
private case object BufferUnderflow extends Result

/** Represents the possibility of failure */
private sealed abstract class MaybeError extends Result {
  final def success: Boolean = this == Continue
}

private object MaybeError {
  def apply(option: Option[Http2Exception]): MaybeError =
    option match {
      case None => Continue
      case Some(ex) => Error(ex)
    }
}

private case object Continue extends MaybeError
private final case class Error(err: Http2Exception) extends MaybeError
