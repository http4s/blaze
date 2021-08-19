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
package pipeline

import scala.util.control.NoStackTrace

object Command {
  trait InboundCommand

  /** Signals that the pipeline [[HeadStage]] is connected and ready to accept read and write
    * requests
    */
  case object Connected extends InboundCommand

  /** Signals to the tail of the pipeline that it has been disconnected and shutdown. Any following
    * reads or writes will result in an exception, [[EOF]], a general Exception signaling the stage
    * is not connected, or otherwise.
    */
  case object Disconnected extends InboundCommand

  /** Signals to the entire pipeline that the [[HeadStage]] has been disconnected and shutdown. Any
    * following reads or writes will result in an exception, [[EOF]] or otherwise
    */
  case object EOF extends Exception("EOF") with InboundCommand with NoStackTrace {
    override def toString: String = getMessage
  }
}
