package org.http4s.blaze
package pipeline

import scala.util.control.NoStackTrace


object Command {

  trait InboundCommand

  trait OutboundCommand

  /** Signals the stages desire to Connect. It may be attempting to read from the pipeline */
  case object Connect extends OutboundCommand

  /** Signals that the pipeline [[HeadStage]] is connected and ready to accept read and write requests */
  case object Connected extends InboundCommand

  /** Signals the tails desire to shutdown. No [[Disconnected]] command should be sent in reply */
  case object Disconnect extends OutboundCommand

  /** Signals to the tail of the pipeline that it has been disconnected and
    * shutdown. Any following reads or writes will result in an exception, [[EOF]],
    * a general Exception signaling the stage is not connected, or otherwise.
    */
  case object Disconnected extends InboundCommand

  /** Signals the the stages a desire to flush the pipeline. This is just a suggestion
    * and is not guaranteed to induce any effect. */
  case object Flush extends OutboundCommand

  /** Signals to the entire pipeline that the [[HeadStage]] has been disconnected and
    * shutdown. Any following reads or writes will result in an exception, [[EOF]]
    * or otherwise
    */
  case object EOF extends Exception("EOF") with InboundCommand with NoStackTrace {
    override def toString() = getMessage
  }

  /** Signals that an unknown error has occured and the tail stages have likely
    * shut down. If the stage cannot recover it should propegate the error. If the
    * [[Error]] reaches the [[HeadStage]], the [[HeadStage]] should shutdown the pipeline.
    *
    * @param e Throwable that was unhandled by the tail of the pipeline.
    */
  case class Error(e: Throwable) extends Exception(e) with OutboundCommand
}
