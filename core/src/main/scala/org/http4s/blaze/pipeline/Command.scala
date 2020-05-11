/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze
package pipeline

import scala.util.control.NoStackTrace

object Command {
  trait InboundCommand

  /** Signals that the pipeline [[HeadStage]] is connected and ready to accept read and write requests */
  case object Connected extends InboundCommand

  /** Signals to the tail of the pipeline that it has been disconnected and
    * shutdown. Any following reads or writes will result in an exception, [[EOF]],
    * a general Exception signaling the stage is not connected, or otherwise.
    */
  case object Disconnected extends InboundCommand

  /** Signals to the entire pipeline that the [[HeadStage]] has been disconnected and
    * shutdown. Any following reads or writes will result in an exception, [[EOF]]
    * or otherwise
    */
  case object EOF extends Exception("EOF") with InboundCommand with NoStackTrace {
    override def toString: String = getMessage
  }
}
