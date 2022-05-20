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

sealed trait Priority {
  def isDefined: Boolean
}

object Priority {

  /** object representing the contents of a PRIORITY frame
    *
    * This is also used for the HEADERS frame which is logically a series of headers with a possible
    * PRIORITY frame
    */
  final case class Dependent(dependentStreamId: Int, exclusive: Boolean, priority: Int)
      extends Priority {
    require(0 <= dependentStreamId, "Invalid stream dependency")
    require(0 < priority && priority <= 256, "Weight must be 1 to 256")

    def isDefined: Boolean = true
  }

  /** Represents a lack of priority */
  case object NoPriority extends Priority {
    def isDefined: Boolean = false
  }
}
